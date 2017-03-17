package services

import java.io.StringReader
import java.security.KeyPair
import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.http.{FileMimeTypes, HeaderNames, Status}
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsArray, JsObject, JsPath, JsResult, JsResultException, JsValue, JsonValidationError, Reads, _}
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.MultipartFormData.{DataPart, FilePart}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class MetaMind(configuration: Configuration, wsClient: WSClient, fileMimeTypes: FileMimeTypes, cache: SyncCacheApi)(implicit executionContext: ExecutionContext) {

  val baseUrl = configuration.get[String]("metamind.url") + "v1"

  val email: String = configuration.get[String]("metamind.email")

  val keyPair: KeyPair = {
    val privateKeyString = configuration.get[String]("metamind.key")

    val stringReader = new StringReader(privateKeyString)

    val pemParser = new PEMParser(stringReader)

    val pemObject = pemParser.readObject()

    new JcaPEMKeyConverter().getKeyPair(pemObject.asInstanceOf[PEMKeyPair])
  }

  def bearerToken: Future[String] = {
    val url = s"$baseUrl/oauth2/token"

    val claim = JwtClaim(subject = Some(email), audience = Some(Set(url))).expiresIn(60 * 15)
    val assertion = JwtJson.encode(claim, keyPair.getPrivate, JwtAlgorithm.RS256)

    val params = Map(
      "grant_type" -> Seq("urn:ietf:params:oauth:grant-type:jwt-bearer"),
      "assertion" -> Seq(assertion)
    )

    wsClient.url(url).post(params).flatMap(status(Status.OK)).flatMap { json =>
      (json \ "access_token").validate[String].toFuture.map { accessToken =>
        cache.set("accessToken", accessToken, 10.minutes)
        accessToken
      }
    }
  }

  def allDatasets: Future[JsArray] = {
    ws("/vision/datasets")(_.get()).flatMap(status(Status.OK)).flatMap { json =>
      (json \ "data").validate[JsArray].toFuture
    }
  }

  def getDataset(dataset: Int): Future[JsObject] = {
    ws(s"/vision/datasets/$dataset")(_.get()).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def getDataset(name: String): Future[JsObject] = {
    allDatasets.flatMap { jsArray =>
      val maybeDataset = jsArray.value.find(_.\("name").as[String] == name)
      maybeDataset.fold(Future.failed[JsObject](new Exception(s"Dataset named $name not found"))) { jsValue =>
        Future.successful(jsValue.as[JsObject])
      }
    }
  }

  def createDataset(name: String): Future[JsObject] = {
    val formData = Source.single(DataPart("name", name))
    ws("/vision/datasets")(_.post(formData)).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def deleteDataset(id: Int): Future[Unit] = {
    ws(s"/vision/datasets/$id")(_.delete()).flatMap(nocontent)
  }

  def createLabel(dataset: Int, name: String): Future[JsObject] = {
    val formData = Source.single(DataPart("name", name))
    ws(s"/vision/datasets/$dataset/labels")(_.post(formData)).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def getLabel(dataset: Int, label: Int): Future[JsObject] = {
    ws(s"/vision/datasets/$dataset/labels/$label")(_.get()).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def createExample(dataset: Int, label: Int, filename: String, image: Source[ByteString, Any]): Future[JsObject] = {
    val namePart = DataPart("name", filename)
    val labelIdPart = DataPart("labelId", label.toString)

    val contentType = fileMimeTypes.forFileName(filename)

    val filePart = FilePart("data", filename, contentType, image)

    val formData = Source(List(namePart, labelIdPart, filePart))

    ws(s"/vision/datasets/$dataset/examples")(_.post(formData)).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def createDatasetFromZip(filename: String, zip: Source[ByteString, Any], sync: Boolean = false): Future[JsObject] = {
    val contentType = fileMimeTypes.forFileName(filename)

    val filePart = FilePart("data", filename, contentType, zip)

    val url = s"/vision/datasets/upload" + (if (sync) "/sync" else "")

    ws(url)(_.post(Source.single(filePart))).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def createDatasetFromUrl(url: String, sync: Boolean = false): Future[JsObject] = {
    val pathPart = DataPart("path", url)

    val uploadUrl = s"/vision/datasets/upload" + (if (sync) "/sync" else "")

    ws(uploadUrl)(_.post(Source.single(pathPart))).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def trainDataset(dataset: Int, name: String): Future[JsObject] = {
    val formData = Source(List(DataPart("datasetId", dataset.toString), DataPart("name", name)))
    ws("/vision/train")(_.post(formData)).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def trainingStatus(modelId: String): Future[JsObject] = {
    ws(s"/vision/train/$modelId")(_.get()).flatMap(status(Status.OK)).flatMap { json =>
      json.validate[JsObject].toFuture
    }
  }

  def allModels(dataset: Int): Future[Seq[MetaMind.Model]] = {
    ws(s"/vision/datasets/$dataset/models")(_.get()).flatMap(status(Status.OK)).flatMap { json =>
      (json \ "data").validate[Seq[MetaMind.Model]].toFuture
    }
  }

  def predictWithImage(modelId: String, filename: String, image: Source[ByteString, Any]): Future[JsObject] = {
    val contentType = fileMimeTypes.forFileName(filename)

    val modelIdPart = DataPart("modelId", modelId)

    val filePart = FilePart("sampleContent", filename, contentType, image)

    val formData = Source(List(modelIdPart, filePart))
      ws("/vision/predict")(_.post(formData)).flatMap(status(Status.OK)).flatMap { json =>
        json.validate[JsObject].toFuture
      }
  }

  private def ws(path: String)(f: (WSRequest) => Future[WSResponse]): Future[WSResponse] = {
    // todo: bearerToken retry
    val bearerTokenFuture = cache.get[String]("accessToken").fold(bearerToken)(Future.successful)

    bearerTokenFuture.flatMap { bearerToken =>
      val wsRequest = wsClient.url(baseUrl + path).withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $bearerToken")
      f(wsRequest)
    }
  }

  private def nocontent(response: WSResponse): Future[Unit] = {
    if (response.status == Status.NO_CONTENT) {
      Future.successful(Unit)
    }
    else {
      Future.failed(new IllegalStateException(s"Unexpected Response: ${response.status} ${response.body}"))
    }
  }

  private def status(status: Int)(response: WSResponse): Future[JsValue] = {
    if (response.status == status) {
      Future.fromTry(Try(response.json))
    }
    else {
      Future.failed(new IllegalStateException(s"Unexpected Response: ${response.status} ${response.body}"))
    }
  }

  private implicit class RichJsResult[T](jsResult: JsResult[T]) {

    private def errorsToFuture(errors: Seq[(JsPath, Seq[JsonValidationError])]): Future[T] = {
      Future.failed(JsResultException(errors))
    }

    def toFuture: Future[T] = jsResult.fold(errorsToFuture, Future.successful)
  }

}

object MetaMind {
  case class Model(id: String, name: String, status: String, progress: Double, failureMsg: Option[String], updatedAt: OffsetDateTime)

  object Model {
    implicit val jsonReads: Reads[Model] = (
      (__ \ "modelId").read[String] ~
      (__ \ "name").read[String] ~
      (__ \ "status").read[String] ~
      (__ \ "progress").read[Double] ~
      (__ \ "failureMsg").readNullable[String] ~
      (__ \ "updatedAt").read[OffsetDateTime](Reads.offsetDateTimeReads("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
    )(Model.apply _)
  }

}
