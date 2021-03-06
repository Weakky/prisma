package com.prisma.api.server

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import com.prisma.akkautil.http.Server
import com.prisma.akkautil.throttler.Throttler
import com.prisma.akkautil.throttler.Throttler.ThrottleBufferFullException
import com.prisma.api.schema.APIErrors.ProjectNotFound
import com.prisma.api.schema.CommonErrors.ThrottlerBufferFullException
import com.prisma.api.schema.{SchemaBuilder, UserFacingError}
import com.prisma.api.{ApiDependencies, ApiMetrics}
import cool.graph.cuid.Cuid.createCuid
import com.prisma.metrics.extensions.TimeResponseDirectiveImpl
import com.prisma.shared.models.{ProjectId, ProjectWithClientId}
import com.prisma.logging.{LogData, LogKey}
import com.prisma.logging.LogDataWrites.logDataWrites
import com.prisma.util.env.EnvUtils
import play.api.libs.json.Json
import spray.json._

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try

case class ApiServer(
    schemaBuilder: SchemaBuilder,
    prefix: String = ""
)(
    implicit apiDependencies: ApiDependencies,
    system: ActorSystem,
    materializer: ActorMaterializer
) extends Server
    with LazyLogging {
  import system.dispatcher

  val log: String => Unit = (msg: String) => logger.info(msg)
  val requestPrefix       = "api"
  val projectFetcher      = apiDependencies.projectFetcher

  import scala.concurrent.duration._

  lazy val unthrottledProjectIds = sys.env.get("UNTHROTTLED_PROJECT_IDS") match {
    case Some(envValue) => envValue.split('|').filter(_.nonEmpty).toVector.map(ProjectId.fromEncodedString)
    case None           => Vector.empty
  }

  lazy val throttler: Option[Throttler[ProjectId]] = {
    for {
      throttlingRate    <- EnvUtils.asInt("THROTTLING_RATE")
      maxCallsInFlights <- EnvUtils.asInt("THROTTLING_MAX_CALLS_IN_FLIGHT")
    } yield {
      val per = EnvUtils.asInt("THROTTLING_RATE_PER_SECONDS").getOrElse(1)
      Throttler[ProjectId](
        groupBy = pid => pid.name + "_" + pid.stage,
        amount = throttlingRate.toInt,
        per = per.seconds,
        timeout = 25.seconds,
        maxCallsInFlight = maxCallsInFlights.toInt
      )
    }
  }

  val innerRoutes = extractRequest { _ =>
    val requestId            = requestPrefix + ":api:" + createCuid()
    val requestBeginningTime = System.currentTimeMillis()

    def logRequestEnd(projectId: String, throttledBy: Long = 0) = {
      val end            = System.currentTimeMillis()
      val actualDuration = end - requestBeginningTime - throttledBy
      ApiMetrics.requestDuration.record(actualDuration, Seq(projectId))
      ApiMetrics.requestCounter.inc(projectId)
      log(
        Json
          .toJson(
            LogData(
              key = LogKey.RequestComplete,
              requestId = requestId,
              projectId = Some(projectId),
              payload = Some(
                Map(
                  "request_duration" -> (end - requestBeginningTime),
                  "throttled_by"     -> throttledBy
                ))
            )
          )
          .toString())
    }

    def throttleApiCallIfNeeded(projectId: ProjectId, rawRequest: RawRequest) = {
      throttler match {
        case Some(throttler) if !unthrottledProjectIds.contains(projectId) => throttledCall(projectId, rawRequest, throttler)
        case _                                                             => unthrottledCall(projectId, rawRequest)
      }
    }

    def unthrottledCall(projectId: ProjectId, rawRequest: RawRequest) = {
      val result = handleRequestForPublicApi(projectId, rawRequest)
      complete(result)
    }

    def throttledCall(projectId: ProjectId, rawRequest: RawRequest, throttler: Throttler[ProjectId]) = {
      val result = throttler.throttled(projectId) { () =>
        handleRequestForPublicApi(projectId, rawRequest)
      }
      onComplete(result) {
        case scala.util.Success(result) =>
          respondWithHeader(RawHeader("Throttled-By", result.throttledBy.toString + "ms")) {
            complete(result.result)
          }

        case scala.util.Failure(_: ThrottleBufferFullException) =>
          throw ThrottlerBufferFullException()

        case scala.util.Failure(exception) => // just propagate the exception
          throw exception
      }
    }

    def handleRequestForPublicApi(projectId: ProjectId, rawRequest: RawRequest) = {
      val result = apiDependencies.requestHandler.handleRawRequestForPublicApi(projectId.asString, rawRequest)
      result.onComplete { _ =>
        logRequestEnd(projectId.asString)
      }
      result
    }

    logger.info(Json.toJson(LogData(LogKey.RequestNew, requestId)).toString())
    pathPrefix(Segments(min = 2, max = 4)) { segments =>
      post {
        def removeLastElementIfInSet(elements: List[String], set: Set[String]) = {
          if (set.contains(elements.last)) {
            elements.dropRight(1)
          } else {
            elements
          }
        }
        val actualSegments    = removeLastElementIfInSet(segments, Set("private", "import", "export"))
        val projectId         = ProjectId.fromSegments(actualSegments)
        val projectIdAsString = projectId.asString

        val apiSegment = if (segments.size == 3 || segments.size == 4) {
          segments.last
        } else {
          ""
        }

        handleExceptions(toplevelExceptionHandler(requestId)) {
          extractRawRequest(requestId) { rawRequest =>
            apiSegment match {
              case "private" =>
                val result = apiDependencies.requestHandler.handleRawRequestForPrivateApi(projectId = projectIdAsString, rawRequest = rawRequest)
                result.onComplete(_ => logRequestEnd(projectIdAsString))
                complete(result)

              case "import" =>
                withRequestTimeout(5.minutes) {
                  val result = apiDependencies.requestHandler.handleRawRequestForImport(projectId = projectIdAsString, rawRequest = rawRequest)
                  result.onComplete(_ => logRequestEnd(projectIdAsString))
                  complete(result)
                }

              case "export" =>
                withRequestTimeout(5.minutes) {
                  val result = apiDependencies.requestHandler.handleRawRequestForExport(projectId = projectIdAsString, rawRequest = rawRequest)
                  result.onComplete(_ => logRequestEnd(projectIdAsString))
                  complete(result)
                }

              case _ =>
                throttleApiCallIfNeeded(projectId, rawRequest)
            }
          }
        }
      } ~ get {
        getFromResource("graphiql.html")
      }
    }
  }

  def extractRawRequest(requestId: String)(fn: RawRequest => Route): Route = {
    optionalHeaderValueByName("Authorization") { authorizationHeader =>
      TimeResponseDirectiveImpl(ApiMetrics).timeResponse {
        optionalHeaderValueByName("x-graphcool-source") { graphcoolSourceHeader =>
          entity(as[JsValue]) { requestJson =>
            extractClientIP { clientIp =>
              respondWithHeader(RawHeader("Request-Id", requestId)) {
                fn(
                  RawRequest(
                    id = requestId,
                    json = requestJson,
                    ip = clientIp.toString,
                    sourceHeader = graphcoolSourceHeader,
                    authorizationHeader = authorizationHeader
                  )
                )
              }
            }
          }
        }
      }
    }
  }

  def fetchProject(projectId: String): Future[ProjectWithClientId] = {
    val result = projectFetcher.fetch(projectIdOrAlias = projectId)

    result map {
      case None         => throw ProjectNotFound(projectId)
      case Some(schema) => schema
    }
  }

  def toplevelExceptionHandler(requestId: String) = ExceptionHandler {
    case e: UserFacingError =>
      complete(OK -> JsObject("code" -> JsNumber(e.code), "requestId" -> JsString(requestId), "error" -> JsString(e.getMessage)))

    case e: Throwable =>
      println(e.getMessage)
      e.printStackTrace()
      apiDependencies.reporter.report(e)
      complete(InternalServerError -> JsObject("errors" -> JsArray(JsObject("requestId" -> JsString(requestId), "message" -> JsString(e.getMessage)))))
  }
}
