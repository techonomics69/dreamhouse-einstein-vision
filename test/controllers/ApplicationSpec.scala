package controllers

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.{AsyncWordSpec, BeforeAndAfterAll, EitherValues, MustMatchers}
import play.api.cache.ehcache.EhCacheComponents
import play.api.http.{DefaultFileMimeTypes, DefaultHttpErrorHandler, HeaderNames, HttpConfiguration, HttpErrorHandler}
import play.api.i18n.{DefaultLangsProvider, DefaultMessagesApiProvider, Langs, MessagesApi}
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.Files.{DefaultTemporaryFileCreator, DefaultTemporaryFileReaper, TemporaryFileCreator, TemporaryFileReaper, TemporaryFileReaperConfiguration}
import play.api.libs.json.{JsArray, Json}
import play.api.libs.ws.ahc.AhcWSClient
import play.api.mvc.{AnyContent, BodyParser, DefaultActionBuilder, DefaultControllerComponents, MultipartFormData, PlayBodyParsers}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Configuration, Environment, Mode}
import play.core.parsers.Multipart
import play.core.parsers.Multipart.FileInfo
import services.MetaMind

import scala.concurrent.ExecutionContext
import scala.util.Random

class ApplicationSpec extends AsyncWordSpec with EitherValues with MustMatchers with BeforeAndAfterAll {

  implicit lazy val actorSystem = ActorSystem()

  implicit lazy val ec = actorSystem.dispatcher

  implicit lazy val materializer = ActorMaterializer()

  lazy val config = Configuration(ConfigFactory.load())

  lazy val wsClient = AhcWSClient()

  lazy val env = Environment(new File("."), getClass.getClassLoader, Mode.Test)

  lazy val httpConfiguration = HttpConfiguration.fromConfiguration(config, env)

  lazy val fileMimeTypes = new DefaultFileMimeTypes(httpConfiguration.fileMimeTypes)

  lazy val applicationLifecycle = new DefaultApplicationLifecycle()

  lazy val tempFileReaper: TemporaryFileReaper = new DefaultTemporaryFileReaper(actorSystem, TemporaryFileReaperConfiguration.fromConfiguration(config))
  lazy val tempFileCreator: TemporaryFileCreator = new DefaultTemporaryFileCreator(applicationLifecycle, tempFileReaper)

  lazy val httpErrorHandler: HttpErrorHandler = new DefaultHttpErrorHandler(env, config, None, None)

  lazy val langs: Langs = new DefaultLangsProvider(config).get
  lazy val messagesApi: MessagesApi = new DefaultMessagesApiProvider(env, config, langs, httpConfiguration).get

  lazy val playBodyParsers: PlayBodyParsers = PlayBodyParsers(httpConfiguration.parser, httpErrorHandler, materializer, tempFileCreator)
  lazy val defaultBodyParser: BodyParser[AnyContent] = playBodyParsers.default
  lazy val defaultActionBuilder: DefaultActionBuilder = DefaultActionBuilder(defaultBodyParser)

  lazy val controllerComponents = DefaultControllerComponents(defaultActionBuilder, playBodyParsers, messagesApi, langs, fileMimeTypes, ec)

  lazy val cacheComponents = new EhCacheComponents {
    override def environment: Environment = env

    override def configuration: Configuration = config

    override def applicationLifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle

    override implicit def executionContext: ExecutionContext = ec
  }

  lazy val assetsConfiguration: AssetsConfiguration = AssetsConfiguration.fromConfiguration(config, env.mode)

  lazy val assetsMetadata: AssetsMetadata = new AssetsMetadataProvider(env, assetsConfiguration, fileMimeTypes, applicationLifecycle).get

  lazy val assetFinder: AssetsFinder = assetsMetadata

  lazy val metaMind = new MetaMind(config, wsClient, fileMimeTypes, cacheComponents.defaultCacheApi.sync)

  lazy val datasetName = Random.alphanumeric.take(8).mkString

  lazy val application = new Application(datasetName, metaMind, controllerComponents)(assetFinder, actorSystem, materializer)

  "index" must {
    "work" in {
      val request = FakeRequest()
      application.index(request).map { result =>
        result.header.status must equal (OK)
      }
    }
  }

  "upload" must {
    "work with a FilePart" in {
      val filename = "cool-cat.jpg"
      val dataParts = Map("label" -> Seq("cool"))
      val inputStream = getClass.getResourceAsStream("/" + filename)
      val coolcatSource = StreamConverters.fromInputStream(() => inputStream)
      val contentType = fileMimeTypes.forFileName(filename)
      val contentFuture = coolcatSource.runFold(ByteString())(_ ++ _)

      contentFuture.flatMap { content =>
        val files = Seq(MultipartFormData.FilePart("image", "cool-cat.jpg", contentType, content))
        val body = MultipartFormData(dataParts, files, Seq.empty[MultipartFormData.BadPart])
        val request = FakeRequest().withBody(body)

        application.upload(request).flatMap { result =>
          result.body.consumeData.map { body =>
            val json = Json.parse(body.toArray).as[JsArray].value.head
            val location = (json \ "location").as[String]
            val numExamples = (json \ "label" \ "numExamples").as[Int]

            result.header.status must equal(OK)
            numExamples must equal(1)

            // todo: download location & compare to coolcatSource
          }
        }
      }
    }
  }

  "handleFilePartAsByteString" must {
    "work" in {
      val testByteString = ByteString("asdf")
      val source = Source.single(testByteString)

      val fileInfo = FileInfo("foo", "foo", None)

      val accumulator = application.handleFilePartAsByteString(fileInfo)

      accumulator.run(source).flatMap { filePart =>
        filePart.key must equal ("foo")
        filePart.filename must equal ("foo")
        filePart.ref must equal (testByteString)
      }
    }
  }

  "Multipart.multipartParser" must {

    lazy val body =
      """
        |--boundary
        |Content-Disposition: form-data; name="foo"
        |
        |foo
        |--boundary
        |Content-Disposition: form-data; name="bar"; filename="bar"
        |Content-Type: text/plain
        |
        |bar
        |--boundary--
        |""".stripMargin.lines.mkString("\r\n")

    "not work with too small of a buffer" in {
      val request = FakeRequest().withHeaders(HeaderNames.CONTENT_TYPE -> "multipart/form-data; boundary=boundary")

      val parser = Multipart.multipartParser(0, application.handleFilePartAsByteString, httpErrorHandler)
      val accumulator = parser(request)

      accumulator.run(Source.single(ByteString(body))).flatMap { resultOrFormData =>
        resultOrFormData.left.value.body.consumeData.map { byteString =>
          resultOrFormData.left.value.header.status must equal (REQUEST_ENTITY_TOO_LARGE)
          byteString.decodeString("utf-8") must include ("Memory buffer full on part")
        }
      }
    }
    "work" in {
      val request = FakeRequest().withHeaders(HeaderNames.CONTENT_TYPE -> "multipart/form-data; boundary=boundary")

      val parser = Multipart.multipartParser(Int.MaxValue, application.handleFilePartAsByteString, httpErrorHandler)
      val accumulator = parser(request)

      accumulator.run(Source.single(ByteString(body))).flatMap { resultOrFormData =>
        resultOrFormData.right.value.dataParts.get("foo") must equal (Some(Seq("foo")))
        val filePart = resultOrFormData.right.value.file("bar").get
        filePart.filename must equal ("bar")
        filePart.contentType must equal (Some("text/plain"))
        filePart.ref.decodeString("utf-8") must equal ("bar")
      }
    }
  }

  override def beforeAll = {
    await(metaMind.createDataset(datasetName))
  }

  override def afterAll {
    await {
      metaMind.getDataset(datasetName).flatMap { dataset =>
        val id = (dataset \ "id").as[Int]
        metaMind.deleteDataset(id).map { _ =>
          wsClient.close()
          actorSystem.terminate()
          cacheComponents.ehCacheManager.shutdown()
        }
      }
    }
  }

}
