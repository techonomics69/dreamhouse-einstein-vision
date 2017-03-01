package controllers

import akka.stream.scaladsl.FileIO
import play.api.libs.Files
import play.api.libs.json.JsObject
import play.api.mvc.{AbstractController, ControllerComponents, MultipartFormData, Request}
import services.MetaMind

import scala.concurrent.Future


class Application(metaMind: MetaMind, components: ControllerComponents)(implicit assetsFinder: AssetsFinder) extends AbstractController(components) {

  implicit val executionContext = components.executionContext

  val datasetName = "Dreamhouse"

  private def getDataset(): Future[JsObject] = {
    metaMind.allDatasets.flatMap { jsArray =>
      val maybeDataset = jsArray.value.find(_.\("name").as[String] == datasetName)
      maybeDataset.fold(Future.failed[JsObject](new Exception(s"Dataset named $datasetName not found"))) { jsValue =>
        Future.successful(jsValue.as[JsObject])
      }
    }
  }

  private def getOrCreateDataset(): Future[JsObject] = {
    getDataset().recoverWith {
      case _ =>
        metaMind.createDataset(datasetName)
    }
  }

  def index = Action.async {
    for {
      dataset <- getOrCreateDataset()
      datasetId = (dataset \ "id").as[Int]
      models <- metaMind.allModels(datasetId).recover { case _ => Seq.empty[MetaMind.Model] }
    } yield {
      val name = (dataset \ "name").as[String]
      val labels = (dataset \ "labelSummary" \ "labels").as[Seq[JsObject]].map(_.\("name").as[String])
      val totalExamples = (dataset  \ "totalExamples").as[Int]
      Ok(views.html.index(name, labels, totalExamples, models))
    }
  }

  /*
  // todo
  private def handleFilePartAsByteString: Multipart.FilePartHandler[Source[ByteString, Any]] = {
    case FileInfo(partName, filename, contentType) =>
      println(partName, filename, contentType)
      Accumulator.source[ByteString].map { source =>
        println("source", source)

        val empty = Source.single(ByteString("asdf"))

        val filePart: FilePart[Source[ByteString, Any]] = FilePart(partName, filename, contentType, empty)
        println("filePart", filePart)
        filePart
      }
  }
  */

  // todo: don't use temp file
  // todo: fix race condition with multiple uploads simutansous uploads for a non-existant label
  def upload = Action(parse.multipartFormData).async { request: Request[MultipartFormData[Files.TemporaryFile]] =>
    val maybeFilename = request.body.asFormUrlEncoded.get("filename").flatMap(_.headOption)
    val maybeLabel = request.body.asFormUrlEncoded.get("label").flatMap(_.headOption)
    val maybeFilePart = request.body.file("image")

    (maybeFilename, maybeLabel, maybeFilePart) match {
      case (Some(filename), Some(label), Some(filePart)) =>
        getDataset().flatMap { dataset =>
          val datasetId = (dataset \ "id").as[Int]
          val allLabels = (dataset \ "labelSummary" \ "labels").as[Seq[JsObject]]
          val maybeLabel = allLabels.find(_.\("name").as[String] == label)
          val labelFuture = maybeLabel.fold {
            metaMind.createLabel(datasetId, label)
          } { jsValue => Future.successful(jsValue.as[JsObject]) }

          labelFuture.flatMap { label =>
            val labelId = (label \ "id").as[Int]
            val fileSource = FileIO.fromPath(filePart.ref.path)
            metaMind.createExample(datasetId, labelId, filename, fileSource).map { _ =>
              Ok
            }
          }
        } recover {
          case e: Exception =>
            InternalServerError(e.getMessage)
        }
      case _ =>
        Future.successful(BadRequest("Required data was not sent"))
    }
  }

  def create = Action(parse.formUrlEncoded).async { request: Request[Map[String, Seq[String]]] =>
    val maybeUrl = request.body.get("url").flatMap(_.headOption)

    maybeUrl.fold(Future.successful(BadRequest("The url form element was not specified"))) { url =>
      getDataset().flatMap { dataset =>
        val datasetId = (dataset \ "id").as[Int]
        for {
          _ <- metaMind.deleteDataset(datasetId)
          newDataset <- metaMind.createDatasetFromUrl(url)
        } yield Redirect(routes.Application.index())
      }
    }
  }

  def train = Action.async {
    metaMind.allDatasets.flatMap { allDatasets =>
      val maybeDataset = allDatasets.value.find(_.\("name").as[String] == datasetName)
      maybeDataset.fold(Future.successful(InternalServerError("Dataset did not exist"))) { dataset =>
        val datasetId = (dataset \ "id").as[Int]
        val datasetName = (dataset \ "name").as[String]
        metaMind.allModels(datasetId).flatMap { models =>
          val trainName = datasetName + " v" + (models.size + 1)
          metaMind.trainDataset(datasetId, trainName).map { _ =>
            Redirect(routes.Application.index())
          }
        }
      }
    }
  }

  // todo: don't use a tempfile
  def predict = Action(parse.multipartFormData).async { request: Request[MultipartFormData[Files.TemporaryFile]] =>
    val maybeModelId = request.body.asFormUrlEncoded.get("modelId").flatMap(_.headOption)
    val maybeFilename = request.body.asFormUrlEncoded.get("filename").flatMap(_.headOption)
    val maybeSampleContent = request.body.file("sampleContent")

    (maybeFilename, maybeSampleContent) match {
      case (Some(filename), Some(sampleContent)) =>
        val fileSource = FileIO.fromPath(sampleContent.ref.path)

        val modelIdFuture = maybeModelId.fold {
          for {
            dataset <- getOrCreateDataset()
            datasetId = (dataset \ "id").as[Int]
            models <- metaMind.allModels(datasetId)
            sortedModels = models.filter(_.status == "SUCCEEDED").sortBy(_.updatedAt)
            newestModel <- sortedModels.headOption.fold(Future.failed[MetaMind.Model](new Exception("")))(Future.successful)
          } yield newestModel.id
        } (Future.successful)

        modelIdFuture.flatMap { modelId =>
          metaMind.predictWithImage(modelId, filename, fileSource).map { json =>
            Ok(json)
          }
        }
      case _ =>
        Future.successful(BadRequest("Required data was not sent"))
    }
  }

}
