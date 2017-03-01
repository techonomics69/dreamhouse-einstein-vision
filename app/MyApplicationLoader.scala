import controllers.AssetsComponents
import play.api.ApplicationLoader.Context
import play.api.cache.ehcache.EhCacheComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{DefaultActionBuilder, DefaultControllerComponents, PlayBodyParsers}
import play.api.routing.Router
import play.api.{Application, ApplicationLoader, BuiltInComponentsFromContext, LoggerConfigurator}
import router.Routes
import services.MetaMind

class MyApplicationLoader extends ApplicationLoader {
  override def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach(_.configure(context.environment))
    new MyComponents(context).application
  }
}

class MyComponents(context: Context) extends BuiltInComponentsFromContext(context) with AhcWSComponents with AssetsComponents with EhCacheComponents {

  lazy val parsers: PlayBodyParsers = PlayBodyParsers(httpConfiguration.parser, httpErrorHandler, materializer, tempFileCreator)

  lazy val actionBuilder = DefaultActionBuilder(parsers.anyContent)

  lazy val controllerComponents = DefaultControllerComponents(actionBuilder, parsers, messagesApi, langs, fileMimeTypes, executionContext)

  lazy val metaMind = new MetaMind(context.initialConfiguration, wsClient, fileMimeTypes, defaultCacheApi.sync)(executionContext)

  lazy val applicationController = new controllers.Application(metaMind, controllerComponents)(assetFinder)
  lazy val assetsBuilder = new controllers.AssetsBuilder(httpErrorHandler, assetsMetadata)

  override def router: Router = new Routes(httpErrorHandler, applicationController, assetsBuilder)
}

