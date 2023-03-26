enablePlugins(ScalaJSEsbuildDomPlugin)

scalaVersion := "2.13.8"

scalaJSUseMainModuleInitializer := true

libraryDependencies ++= Seq(
  "org.scala-js" %%% "scalajs-dom" % "2.2.0",
  "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
)

lazy val perConfigSettings = Seq(
  jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
    org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
      .Config()
      .withEnv(
        Map(
          "NODE_PATH" -> ((esbuildInstall / crossTarget).value / "node_modules").absolutePath
        )
      )
  )
)
inConfig(Compile)(perConfigSettings)
inConfig(Test)(perConfigSettings)

//TODO setup production bundling with hashes, consider altering esbuildBundle for it
//TODO setup injection of scripts for both serve and production bundling
