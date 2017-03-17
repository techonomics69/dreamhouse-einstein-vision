lazy val root = (project in file(".")).enablePlugins(PlayScala)

name := "dreamhouse-einstein-vision"

scalaVersion := "2.12.1"

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  ws,
  cache,
  "com.pauldijou" %% "jwt-play-json" % "0.12.0",
  "org.webjars.npm" % "vue" % "2.1.10",
  "org.webjars.npm" % "vue-resource" % "1.2.0",
  "org.webjars.npm" % "salesforce-ux__design-system" % "2.2.1",
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.0.0-M1" % "test"
)

pipelineStages := Seq(digest, gzip)
