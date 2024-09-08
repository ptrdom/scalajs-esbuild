enablePlugins(ScalaJSEsbuildWebPlugin)

scalaVersion := "2.13.14"

scalaJSUseMainModuleInitializer := true

libraryDependencies ++= Seq(
  "org.scala-js" %%% "scalajs-dom" % "2.8.0",
  "org.scalatest" %%% "scalatest" % "3.2.19" % "test"
)

lazy val perConfigSettings = Seq(
  jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
    org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
      .Config()
      .withEnv(
        Map(
          "NODE_PATH" -> ((esbuildStage / crossTarget).value / "node_modules").absolutePath
        )
      )
  )
)
inConfig(Compile)(perConfigSettings)
inConfig(Test)(perConfigSettings)
