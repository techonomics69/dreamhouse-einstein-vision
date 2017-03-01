package services

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.cache.ehcache.EhCacheComponents
import play.api.http.{DefaultFileMimeTypes, HttpConfiguration}
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.json.JsObject
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.Helpers._
import play.api.{Configuration, Environment, Mode}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

class MetaMindSpec extends PlaySpec with BeforeAndAfterAll {

  implicit lazy val actorSystem = ActorSystem()

  implicit lazy val ec = actorSystem.dispatcher

  implicit lazy val materializer = ActorMaterializer()

  lazy val config = Configuration(ConfigFactory.load())

  lazy val wsClient = AhcWSClient()

  lazy val env = Environment(new File("."), getClass.getClassLoader, Mode.Test)

  lazy val httpConfiguration = HttpConfiguration.fromConfiguration(config, env)

  lazy val fileMimeTypes = new DefaultFileMimeTypes(httpConfiguration.fileMimeTypes)


  val cacheComponents = new EhCacheComponents {
    override def environment: Environment = env

    override def configuration: Configuration = config

    override def applicationLifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle

    override implicit def executionContext: ExecutionContext = ec
  }

  lazy val metaMind = new MetaMind(config, wsClient, fileMimeTypes, cacheComponents.defaultCacheApi.sync)

  def withDataset(f: (Int) => Any): Unit = {
    val name = Random.alphanumeric.take(8).mkString
    val dataset = await(metaMind.createDataset(name))
    val id = (dataset \ "id").as[Int]
    try {
      f(id)
    }
    finally {
      await(metaMind.deleteDataset(id))
    }
  }

  def withLabel(f: (Int, Int) => Any): Unit = {
    withDataset { dataset =>
      val name = Random.alphanumeric.take(16).mkString
      val label = await(metaMind.createLabel(dataset, name))
      val id = (label \ "id").as[Int]
      f(dataset, id)
    }
  }

  def withTrainDataset(f: (Int, String) => Any): Unit = {
    withDataset { dataset =>
      val name = Random.alphanumeric.take(8).mkString
      val train = await(metaMind.trainDataset(dataset, name))
      val modelId = (train \ "modelId").as[String]
      f(dataset, modelId)
    }
  }

  "bearerToken" must {
    "work" in {
      await(metaMind.bearerToken).length must be > 0
    }
  }

  "createDataset" must {
    "work" in {
      val name = Random.alphanumeric.take(8).mkString
      val dataset = await(metaMind.createDataset(name))
      val maybeId = (dataset \ "id").asOpt[Int]
      maybeId must be ('defined)
      val id = maybeId.get
      await(metaMind.deleteDataset(id)) must be (())
    }
  }

  "allDatasets" must {
    "work" in {
      await(metaMind.allDatasets).value.size must be >= 0
    }
  }

  "createLabel" must {
    "work" in withDataset { dataset =>
      val name = Random.alphanumeric.take(8).mkString
      val label = await(metaMind.createLabel(dataset, name))
      (label \ "id").asOpt[Int] must be ('defined)
    }
    "work with spaces" in withDataset { dataset =>
      // todo: create works but uploading examples to this doesn't work
      val name = "Foo Bar"
      val label = await(metaMind.createLabel(dataset, name))
      (label \ "name").as[String] must equal (name)
    }
  }

  "getLabel" must {
    "work" in withLabel { case (datasetId, labelId) =>
      val label = await(metaMind.getLabel(datasetId, labelId))
      (label \ "id").as[Int] must equal (labelId)
    }
  }

  "createExample" must {
    "work" in withLabel { case (datasetId, labelId) =>
      val filename = "cool-cat.jpg"
      val inputStream = getClass.getResourceAsStream("/" + filename)
      val image = StreamConverters.fromInputStream(() => inputStream)
      val example = await(metaMind.createExample(datasetId, labelId, filename, image))
      (example \ "id").asOpt[Int] must be ('defined)
    }
  }

  "createDatasetFromZip" must {
    "work" in {
      val filename = "Cats.zip"
      val inputStream = getClass.getResourceAsStream("/" + filename)
      val zip = StreamConverters.fromInputStream(() => inputStream)
      val dataset = await(metaMind.createDatasetFromZip(filename, zip, true))(Timeout(1.minute))
      (dataset \ "name").as[String] must equal ("Cats")

      val labels = (dataset \ "labelSummary" \ "labels").as[Seq[JsObject]]
      labels.size must equal (1)
      (labels.head \ "name").as[String] must equal ("Cool Cats")
    }
  }

  "createDatasetFromUrl" must {
    "work" in {
      val url = "https://github.com/dreamhouseapp/dreamhouse-pvs-scala/raw/master/test/resources/Cats.zip"
      val dataset = await(metaMind.createDatasetFromUrl(url, true))
      (dataset \ "name").as[String] must equal ("Cats")

      val labels = (dataset \ "labelSummary" \ "labels").as[Seq[JsObject]]
      labels.size must equal (1)
      (labels.head \ "name").as[String] must equal ("Cool Cats")
    }
  }

  "trainDataset" must {
    "work" in withDataset { datasetId =>
      val name = Random.alphanumeric.take(8).mkString
      val train = await(metaMind.trainDataset(datasetId, name))
      (train \ "name").as[String] must equal (name)
    }
  }

  "trainingStatus" must {
    "work" in withTrainDataset { (dataset, modelId) =>
      val status = await(metaMind.trainingStatus(modelId))
      (status \ "modelId").as[String] must equal (modelId)
    }
  }

  "allModels" must {
    "work" in withTrainDataset { (dataset, modelId) =>
      val models = await(metaMind.allModels(dataset))
      models.size must be > 0
    }
  }

  "predictWithImage" must {
    "work" in {
      val inputStream = getClass.getResourceAsStream("/" + "cool-cat.jpg")
      val coolcatSource = StreamConverters.fromInputStream(() => inputStream)

      val result = await(metaMind.predictWithImage("GeneralImageClassifier", "cool-cat.jpg", coolcatSource))

      (result \ "probabilities").as[Seq[JsObject]].size must be > 0
    }
  }

  override def afterAll {
    wsClient.close()
    actorSystem.terminate()
  }

}
