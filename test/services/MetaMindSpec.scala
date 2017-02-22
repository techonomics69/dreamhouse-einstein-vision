package services

import java.io.{BufferedReader, File}
import java.nio.file.Path

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Source, StreamConverters}
import akka.util.{ByteString, Timeout}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.{Configuration, Environment, Mode}
import play.api.http.{DefaultFileMimeTypes, HttpConfiguration}
import play.api.libs.json.JsObject
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.Helpers._

import scala.util.Random
import scala.concurrent.duration._

class MetaMindSpec extends PlaySpec with BeforeAndAfterAll {

  implicit lazy val actorSystem = ActorSystem()

  implicit lazy val executionContext = actorSystem.dispatcher

  implicit lazy val materializer = ActorMaterializer()

  lazy val configuration = Configuration(ConfigFactory.load())

  lazy val wsClient = AhcWSClient()

  lazy val environment = Environment(new File("."), getClass.getClassLoader, Mode.Test)

  lazy val httpConfiguration = HttpConfiguration.fromConfiguration(configuration, environment)

  lazy val fileMimeTypes = new DefaultFileMimeTypes(httpConfiguration.fileMimeTypes)

  lazy val metaMind = new MetaMind(configuration, wsClient, fileMimeTypes)

  def withDataset(f: (Int) => Any): Unit = {
    val name = Random.alphanumeric.take(8).mkString
    val dataset = await(metaMind.createDataset(name))
    println(dataset)
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
      println(label)
      f(dataset, id)
    }
  }

  def withTrainDataset(f: (String) => Any): Unit = {
    withDataset { dataset =>
      val name = Random.alphanumeric.take(8).mkString
      val train = await(metaMind.trainDataset(dataset, name))
      val modelId = (train \ "modelId").as[String]
      f(modelId)
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

  "trainDataset" must {
    "work" in withDataset { datasetId =>
      val name = Random.alphanumeric.take(8).mkString
      val train = await(metaMind.trainDataset(datasetId, name))
      (train \ "name").as[String] must equal (name)
    }
  }

  "trainingStatus" must {
    "work" in withTrainDataset { modelId =>
      val status = await(metaMind.trainingStatus(modelId))
      (status \ "modelId").as[String] must equal (modelId)
    }
  }

  "allModels" must {
    "work" in withTrainDataset { modelId =>
      // todo
      fail
    }
  }

  "predictWithImage" must {
    "work" in withTrainDataset { modelId =>
      // todo
      fail
    }
  }

  override def afterAll {
    wsClient.close()
    actorSystem.terminate()
  }

}
