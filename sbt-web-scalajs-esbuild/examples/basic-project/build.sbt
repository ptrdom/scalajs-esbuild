ThisBuild / scalaVersion := "2.13.16"

lazy val `basic-project` = (project in file(".")).aggregate(client, server)

lazy val client = project
  .in(file("client"))
  .enablePlugins(ScalaJSEsbuildWebPlugin)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0",
      "org.scala-js" %%% "scalajs-dom" % "2.8.0"
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
      Seq(
        "org.apache.pekko" %% "pekko-actor-typed" % "1.1.3",
        "org.apache.pekko" %% "pekko-stream" % "1.1.3",
        "org.apache.pekko" %% "pekko-http" % "1.2.0"
      )
    }
  )
