package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import play.api.libs.json.{JsArray, JsObject}
import play.api.libs.streams.Accumulator
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc.{AbstractController, AnyContent, ControllerComponents, MultipartFormData, Request}
import play.core.parsers.Multipart
import play.core.parsers.Multipart.FileInfo
import services.MetaMind

import scala.concurrent.Future


class Application(datasetName: String, metaMind: MetaMind, components: ControllerComponents)(implicit assetsFinder: AssetsFinder, actorSystem: ActorSystem, materializer: Materializer) extends AbstractController(components) {

  private[this] implicit val executionContext = components.executionContext

  private def getOrCreateDataset(): Future[JsObject] = {
    metaMind.getDataset(datasetName).recoverWith {
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

  // todo: forward the stream to a Source[ByteString]
  private[controllers] def handleFilePartAsByteString: Multipart.FilePartHandler[ByteString] = {
    case FileInfo(partName, filename, contentType) =>

      // booo.... drain the whole file to memory
      val sink = Sink.fold[ByteString, ByteString](ByteString())(_ ++ _)

      Accumulator(sink).map { byteString =>
        FilePart(partName, filename, contentType, byteString)
      }
  }

  def upload = Action(parse.multipartFormData(handleFilePartAsByteString)).async { request: Request[MultipartFormData[ByteString]] =>
    val maybeLabel = request.body.dataParts.get("label").flatMap(_.headOption)

    maybeLabel.fold(Future.successful(BadRequest("Required data was not sent"))) { label =>
      metaMind.getDataset(datasetName).flatMap { dataset =>
        val datasetId = (dataset \ "id").as[Int]
        val allLabels = (dataset \ "labelSummary" \ "labels").as[Seq[JsObject]]
        val maybeLabel = allLabels.find(_.\("name").as[String] == label)
        val labelFuture = maybeLabel.fold {
          metaMind.createLabel(datasetId, label)
        } { jsValue => Future.successful(jsValue.as[JsObject]) }

        labelFuture.flatMap { label =>
          val labelId = (label \ "id").as[Int]

          val exampleFutures = request.body.files.map { filePart =>
            metaMind.createExample(datasetId, labelId, filePart.filename, Source.single(filePart.ref))
          }

          Future.sequence(exampleFutures).map { results =>
            Ok(JsArray(results))
          }
        }
      } recover {
        case e: Exception =>
          InternalServerError(e.getMessage)
      }
    }
  }

  def create = Action(parse.formUrlEncoded).async { request: Request[Map[String, Seq[String]]] =>
    val maybeUrl = request.body.get("url").flatMap(_.headOption)

    maybeUrl.fold(Future.successful(BadRequest("The url form element was not specified"))) { url =>
      metaMind.getDataset(datasetName).flatMap { dataset =>
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

  def predict = Action(parse.multipartFormData(handleFilePartAsByteString)).async { request: Request[MultipartFormData[ByteString]] =>
    val maybeModelId = request.body.dataParts.get("modelId").flatMap(_.headOption)
    val maybeFilename = request.body.dataParts.get("filename").flatMap(_.headOption)
    val maybeSampleContent = request.body.file("sampleContent")

    (maybeFilename, maybeSampleContent) match {
      case (Some(filename), Some(sampleContent)) =>
        val modelIdFuture = maybeModelId.fold {
          for {
            dataset <- getOrCreateDataset()
            datasetId = (dataset \ "id").as[Int]
            modelId <- metaMind.newestModel(datasetId)
          } yield modelId
        } (Future.successful)

        modelIdFuture.flatMap { modelId =>
          metaMind.predictWithImage(modelId, filename, Source.single(sampleContent.ref)).map { json =>
            Ok(json)
          }
        }
      case _ =>
        Future.successful(BadRequest("Required data was not sent"))
    }
  }

  def predictFromUrl = Action.async { request: Request[AnyContent] =>
    val maybeSampleLocation = request.getQueryString("sampleLocation")

    maybeSampleLocation.fold(Future.successful(BadRequest("Required sampleLocation parameter"))) { sampleLocation =>
      for {
        dataset <- getOrCreateDataset()
        datasetId = (dataset \ "id").as[Int]
        modelId <- metaMind.newestModel(datasetId)
        prediction <- metaMind.predictWithUrl(modelId, sampleLocation)
      } yield Ok(prediction)
    }
  }

}
