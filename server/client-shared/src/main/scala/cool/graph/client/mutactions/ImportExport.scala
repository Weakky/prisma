package cool.graph.client.mutactions

import cool.graph.DataItem
import cool.graph.Types.UserData
import cool.graph.client.ClientInjector
import cool.graph.client.database.DatabaseMutationBuilder.MirrorFieldDbValues
import cool.graph.client.database._
import cool.graph.cuid.Cuid
import cool.graph.shared.RelationFieldMirrorColumn
import cool.graph.shared.database.Databases
import cool.graph.shared.models._
import slick.dbio.Effect
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery
import spray.json.{DefaultJsonProtocol, JsArray, JsBoolean, JsFalse, JsNull, JsNumber, JsObject, JsString, JsTrue, JsValue, JsonFormat, RootJsonFormat}

import scala.concurrent.Future
import scala.util.Try

object ImportExportFormat {
  case class StringWithCursor(string: String, modelIndex: Int, nodeRow: Int)

  case class ImportBundle(valueType: String, values: JsArray)
  case class ImportIdentifier(typeName: String, id: String)
  case class ImportRelationSide(identifier: ImportIdentifier, fieldName: String)
  case class ImportNodeValue(index: Int, identifier: ImportIdentifier, values: Map[String, Any])
  case class ImportRelation(index: Int, relationName: String, left: ImportRelationSide, right: ImportRelationSide)
  case class ImportListValue(index: Int, identifier: ImportIdentifier, values: Map[String, Vector[Any]])

  object MyJsonProtocol extends DefaultJsonProtocol {

    //from requestpipelinerunner -> there's 10 different versions of this all over the place -.-
    implicit object AnyJsonFormat extends JsonFormat[Any] {
      def write(x: Any): JsValue = x match {
        case m: Map[_, _]   => JsObject(m.asInstanceOf[Map[String, Any]].mapValues(write))
        case l: List[Any]   => JsArray(l.map(write).toVector)
        case l: Vector[Any] => JsArray(l.map(write))
        case l: Seq[Any]    => JsArray(l.map(write).toVector)
        case n: Int         => JsNumber(n)
        case n: Long        => JsNumber(n)
        case n: BigDecimal  => JsNumber(n)
        case n: Double      => JsNumber(n)
        case s: String      => JsString(s)
        case true           => JsTrue
        case false          => JsFalse
        case v: JsValue     => v
        case null           => JsNull
        case r              => JsString(r.toString)
      }

      def read(x: JsValue): Any = {
        x match {
          case l: JsArray   => l.elements.map(read).toList
          case m: JsObject  => m.fields.mapValues(read)
          case s: JsString  => s.value
          case n: JsNumber  => n.value
          case b: JsBoolean => b.value
          case JsNull       => null
          case _            => sys.error("implement all scalar types!")
        }
      }
    }

    implicit val importBundle: RootJsonFormat[ImportBundle]             = jsonFormat2(ImportBundle)
    implicit val importIdentifier: RootJsonFormat[ImportIdentifier]     = jsonFormat2(ImportIdentifier)
    implicit val importRelationSide: RootJsonFormat[ImportRelationSide] = jsonFormat2(ImportRelationSide)
    implicit val importNodeValue: RootJsonFormat[ImportNodeValue]       = jsonFormat3(ImportNodeValue)
    implicit val importListValue: RootJsonFormat[ImportListValue]       = jsonFormat3(ImportListValue)
    implicit val importRelation: RootJsonFormat[ImportRelation]         = jsonFormat4(ImportRelation)
  }
}

object DataImport {
  import cool.graph.client.mutactions.ImportExportFormat._

  def executeGeneric(project: Project, json: JsValue)(implicit injector: ClientInjector): Future[Vector[String]] = {
    import MyJsonProtocol._

    import scala.concurrent.ExecutionContext.Implicits.global
    val bundle = json.convertTo[ImportBundle]
    val cnt    = bundle.values.elements.length

    val actions = bundle.valueType match {
      case "nodes"      => generateImportNodesDBActions(project, bundle.values.elements.map(_.convertTo[ImportNodeValue]))
      case "relations"  => generateImportRelationsDBActions(project, bundle.values.elements.map(_.convertTo[ImportRelation]))
      case "listvalues" => generateImportListsDBActions(project, bundle.values.elements.map(_.convertTo[ImportListValue]))
    }

    val res: Future[Vector[Try[Int]]]                       = runDBActions(project, actions)
    def messageWithOutConnection(tryelem: Try[Any]): String = tryelem.failed.get.getMessage.substring(tryelem.failed.get.getMessage.indexOf(")") + 1)

    res.map(vector =>
      vector.zipWithIndex.collect {
        case (elem, idx) if elem.isFailure && idx < cnt  => s"Index: $idx Message: ${messageWithOutConnection(elem)}"
        case (elem, idx) if elem.isFailure && idx >= cnt => s"Index: ${idx - cnt} Message: Relay Id Failure ${messageWithOutConnection(elem)}"
    })
  }

  def generateImportNodesDBActions(project: Project, nodes: Vector[ImportNodeValue]): DBIOAction[Vector[Try[Int]], NoStream, Effect.Write] = {
    val items = nodes.map { element =>
      val id                              = element.identifier.id
      val model                           = project.getModelByName_!(element.identifier.typeName)
      val listFields: Map[String, String] = model.scalarFields.filter(_.isList).map(field => field.name -> "[]").toMap
      val values: Map[String, Any]        = element.values ++ listFields + ("id" -> id)
      DatabaseMutationBuilder.createDataItem(project.id, model.name, values).asTry
    }
    val relayIds: TableQuery[ProjectRelayIdTable] = TableQuery(new ProjectRelayIdTable(_, project.id))
    val relay = nodes.map { element =>
      val id    = element.identifier.id
      val model = project.getModelByName_!(element.identifier.typeName)
      val x     = relayIds += ProjectRelayId(id = id, model.id)
      x.asTry
    }
    DBIO.sequence(items ++ relay)
  }

  def generateImportRelationsDBActions(project: Project, relations: Vector[ImportRelation]): DBIOAction[Vector[Try[Int]], NoStream, Effect.Write] = {
    val x = relations.map { element =>
      val fromModel                                                 = project.getModelByName_!(element.left.identifier.typeName)
      val fromField                                                 = fromModel.getFieldByName_!(element.left.fieldName)
      val relationSide: cool.graph.shared.models.RelationSide.Value = fromField.relationSide.get
      val relation: Relation                                        = fromField.relation.get

      val aValue: String = if (relationSide == RelationSide.A) element.left.identifier.id else element.right.identifier.id
      val bValue: String = if (relationSide == RelationSide.A) element.right.identifier.id else element.left.identifier.id

      val aModel: Model = relation.getModelA_!(project)
      val bModel: Model = relation.getModelB_!(project)

      def getFieldMirrors(model: Model, id: String) =
        relation.fieldMirrors
          .filter(mirror => model.fields.map(_.id).contains(mirror.fieldId))
          .map(mirror => {
            val field = project.getFieldById_!(mirror.fieldId)
            MirrorFieldDbValues(
              relationColumnName = RelationFieldMirrorColumn.mirrorColumnName(project, field, relation),
              modelColumnName = field.name,
              model.name,
              id
            )
          })

      val fieldMirrors: List[MirrorFieldDbValues] = getFieldMirrors(aModel, aValue) ++ getFieldMirrors(bModel, bValue)

      DatabaseMutationBuilder.createRelationRow(project.id, relation.id, Cuid.createCuid(), aValue, bValue, fieldMirrors).asTry
    }
    DBIO.sequence(x)
  }

  def generateImportListsDBActions(project: Project, lists: Vector[ImportListValue]): DBIOAction[Vector[Try[Int]], NoStream, Effect.Write] = {
    val x = lists.map { element =>
      val id    = element.identifier.id
      val model = project.getModelByName_!(element.identifier.typeName)
      DatabaseMutationBuilder.updateDataItemListValue(project.id, model.name, id, element.values).asTry
    }
    DBIO.sequence(x)
  }

  def runDBActions(project: Project, actions: DBIOAction[Vector[Try[Int]], NoStream, Effect.Write])(
      implicit injector: ClientInjector): Future[Vector[Try[Int]]] = {
    val db: Databases = injector.globalDatabaseManager.getDbForProject(project)
    db.master.run(actions)
  }
}

object DataExport {
  import cool.graph.client.mutactions.ImportExportFormat._

  // exports nodes returns a string and a nodecursor where to resume or  -1,-1 if no more nodes exist

  def exportNodes(project: Project, dataResolver2: DataResolver, modelIndex: Int, startNodeRow: Int)(
      implicit injector: ClientInjector): Future[StringWithCursor] = {
    implicit val dataResolver = dataResolver2

    resultForCursor(in = "", fileIndex = 0, modelIndex = modelIndex, startNodeRow = startNodeRow, project)
  }

  //to be implemented
  def exportListValues(project: Project, dataResolver2: DataResolver, modelIndex: Int, startNodeRow: Int)(
      implicit injector: ClientInjector): Future[StringWithCursor] = {
    implicit val dataResolver = dataResolver2

    resultForCursor(in = "", fileIndex = 0, modelIndex, startNodeRow, project)
  }

  def isLimitReached(str: String): Boolean = str.length > 1000 // only for testing purposes variable in here

  def resultForCursor(in: String, fileIndex: Int, modelIndex: Int, startNodeRow: Int, project: Project)(
      implicit dataResolver: DataResolver): Future[StringWithCursor] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val modelsWithIndex: List[(Model, Int)] = project.models.zipWithIndex
    val modelLeft                           = modelIndex < project.models.length - 1
    implicit val model: Model               = modelsWithIndex.find(_._2 == modelIndex).get._1
    for {
      modelResult <- resultForModel(in, fileIndex, startNodeRow)
      x <- modelResult.isFull match {
            case false if modelLeft  => resultForCursor(modelResult.out, modelResult.nextFileIndex, modelIndex + 1, startNodeRow = 0, project)
            case false if !modelLeft => Future.successful(StringWithCursor(modelResult.out, -1, -1))
            case true                => Future.successful(StringWithCursor(modelResult.out, modelIndex, modelResult.endNodeRow))
          }
    } yield x
  }

  case class ModelResult(out: String, nextFileIndex: Int, endNodeRow: Int, isFull: Boolean)
  def resultForModel(in: String, fileIndex: Int, startNodeRow: Int)(implicit dataResolver: DataResolver, model: Model): Future[ModelResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    fetchDataItemsPage(startNodeRow).flatMap { page =>
      val pageResult = serializePage(in, page, fileIndex, startOnPage = 0, amount = 1000)

      (pageResult.isFull, page.hasMore) match {
        case (false, true)  => resultForModel(in = pageResult.out, pageResult.nextFileIndex, startNodeRow + 1000)
        case (false, false) => Future.successful(ModelResult(pageResult.out, pageResult.nextFileIndex, endNodeRow = -1, false))
        case (true, _)      => Future.successful(ModelResult(pageResult.out, pageResult.nextFileIndex, startNodeRow + pageResult.used, true))
      }
    }
  }

  case class PageResult(out: String, nextFileIndex: Int, used: Int, isFull: Boolean)
  def serializePage(in: String, page: DataItemsPage, fileIndex: Int, startOnPage: Int, amount: Int)(implicit currentModel: Model): PageResult = {

    val dataItems           = page.items.slice(startOnPage, startOnPage + amount)
    val serializationResult = serializeDataItems(dataItems, fileIndex)
    val combinedString      = if (in.nonEmpty) in + "," + serializationResult.out else serializationResult.out
    val numberSerialized    = dataItems.length
    val isLimitReachedTemp  = isLimitReached(combinedString)
    val fullPageUsed        = startOnPage + amount < page.itemCount

    isLimitReachedTemp match {
      case true if amount != 1    => serializePage(in = in, page = page, fileIndex = fileIndex, startOnPage = startOnPage, amount / 10)
      case false if fullPageUsed  => serializePage(in = combinedString, page, fileIndex + numberSerialized, startOnPage + numberSerialized, amount)
      case true if amount == 1    => PageResult(out = in, nextFileIndex = fileIndex, used = startOnPage, isFull = true)
      case false if !fullPageUsed => PageResult(out = combinedString, nextFileIndex = fileIndex + numberSerialized, startOnPage + numberSerialized + 1, false)
    }
  }

  case class DataItemsPage(items: Seq[DataItem], hasMore: Boolean) { def itemCount: Int = items.length }
  def fetchDataItemsPage(startOnModel: Int)(implicit currentModel: Model, dataResolver: DataResolver): Future[DataItemsPage] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val queryArguments = QueryArguments(skip = Some(startOnModel), after = None, first = Some(1001), None, None, None, None)
    for {
      resolverResult <- dataResolver.resolveByModelExport(currentModel, Some(queryArguments))
    } yield {
      DataItemsPage(resolverResult.items.take(1000), hasMore = resolverResult.items.length > 1000)
    }
  }

  case class SerializeResult(out: String, nextIndex: Int)
  def serializeDataItems(dataItems: Seq[DataItem], startIndex: Int)(implicit currentModel: Model): SerializeResult = {
    var index = startIndex
    val serializedItems: Seq[String] = for {
      item <- dataItems
    } yield {
      val tmp = dataItemToExportLine(index, item)
      index = index + 1
      tmp
    }
    SerializeResult(out = serializedItems.mkString(","), nextIndex = index)
  }

  def dataItemToExportLine(index: Int, item: DataItem)(implicit currentModel: Model): String = {
    import MyJsonProtocol._
    import spray.json._

    val dataValueMap: UserData                          = item.userData
    val createdAtUpdatedAtMap                           = dataValueMap.collect { case (k, Some(v)) if k == "createdAt" || k == "updatedAt" => (k, v) }
    val withoutImplicitFields: Map[String, Option[Any]] = dataValueMap.collect { case (k, v) if k != "createdAt" && k != "updatedAt" => (k, v) }
    val nonListFieldsWithValues: Map[String, Any]       = withoutImplicitFields.collect { case (k, Some(v)) if !currentModel.getFieldByName_!(k).isList => (k, v) }
    val outputMap: Map[String, Any]                     = nonListFieldsWithValues ++ createdAtUpdatedAtMap

    val importIdentifier: ImportIdentifier = ImportIdentifier(currentModel.name, item.id)
    val nodeValue: ImportNodeValue         = ImportNodeValue(index = index, identifier = importIdentifier, values = outputMap)
    nodeValue.toJson.toString
  }
}

object teststuff {

  def readFile(fileName: String): JsValue = {
    import spray.json._
    val json_string = scala.io.Source
      .fromFile(s"/Users/matthias/repos/github.com/graphcool/closed-source/integration-testing/src/test/scala/cool/graph/importData/$fileName")
      .getLines
      .mkString
    json_string.parseJson
  }
}