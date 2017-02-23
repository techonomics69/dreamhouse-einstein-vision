import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import com.typesafe.config.ConfigFactory
import play.api.ApplicationLoader.Context
import play.api.cache.ehcache.EhCacheComponents
import play.api.{Configuration, Environment, Mode}
import play.api.http.{DefaultFileMimeTypes, HttpConfiguration}
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.ws.ahc.AhcWSClient
import services.MetaMind

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

object Admin extends App {

  implicit val actorSystem = ActorSystem()

  implicit val ec = actorSystem.dispatcher

  implicit val materializer = ActorMaterializer()

  val config = Configuration(ConfigFactory.load())

  val wsClient = AhcWSClient()

  val env = Environment(new File("."), getClass.getClassLoader, Mode.Test)

  val httpConfiguration = HttpConfiguration.fromConfiguration(config, env)

  val fileMimeTypes = new DefaultFileMimeTypes(httpConfiguration.fileMimeTypes)

  val cacheComponents = new EhCacheComponents {
    override def environment: Environment = env

    override def configuration: Configuration = config

    override def applicationLifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle

    override implicit def executionContext: ExecutionContext = ec
  }

  val metaMind = new MetaMind(config, wsClient, fileMimeTypes, cacheComponents.defaultCacheApi.sync)

  try {
    if (args(0) == "delete-dataset") {
      val id = args(1).toInt
      println(Await.result(metaMind.deleteDataset(id), 1.minute))
    }
    else if (args(0) == "list-datasets") {
      println(Await.result(metaMind.allDatasets, 1.minute))
    }
    else if (args(0) == "list-models") {
      val id = args(1).toInt
      println(Await.result(metaMind.allModels(id), 1.minute))
    }
    else if (args(0) == "create-label") {
      val datasetId = args(1).toInt
      val label = args(2)
      println(Await.result(metaMind.createLabel(datasetId, label), 1.minute))
    }
    else if (args(0) == "create-example") {
      val datasetId = args(1).toInt
      val labelId = args(2).toInt
      val file = new File(args(3))
      val source = FileIO.fromPath(file.toPath)
      println(Await.result(metaMind.createExample(datasetId, labelId, file.getName, source), 1.minute))
    }
  }
  finally {
    wsClient.close()
    actorSystem.terminate()
  }

}
