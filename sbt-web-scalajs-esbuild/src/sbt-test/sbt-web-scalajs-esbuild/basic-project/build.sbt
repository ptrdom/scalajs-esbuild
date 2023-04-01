ThisBuild / scalaVersion := "2.13.8"

lazy val `basic-project` = (project in file(".")).aggregate(client, server)

lazy val client = project
  .in(file("client"))
  .enablePlugins(ScalaJSEsbuildWebPlugin)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0",
      "org.scala-js" %%% "scalajs-dom" % "2.2.0"
    )
  )

lazy val server = project
  .in(file("server"))
  .enablePlugins(SbtWebScalaJSEsbuildPlugin)
  .settings(
    scalaJSProjects := Seq(client),
    pipelineStages := Seq(scalaJSPipeline),
    Compile / compile := ((Compile / compile) dependsOn scalaJSPipeline).value,
    Runtime / managedClasspath += (Assets / packageBin).value,
    libraryDependencies ++= {
      val akkaVersion = "2.6.20"
      Seq(
        "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
        "com.typesafe.akka" %% "akka-stream" % akkaVersion,
        "com.typesafe.akka" %% "akka-http" % "10.2.10"
      )
    }
  )
