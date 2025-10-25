import java.nio.file.Paths

enablePlugins(ScalaJSEsbuildWebPlugin)

scalaVersion := "2.13.17"

libraryDependencies ++= Seq(
  "org.scala-js" %%% "scalajs-dom" % "2.8.1",
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

import org.scalajs.linker.interface.ModuleInitializer
scalaJSModuleInitializers := Seq(
  ModuleInitializer
    .mainMethodWithArgs("example.Main1", "main")
    .withModuleID("main1"),
  ModuleInitializer
    .mainMethodWithArgs("example.Main2", "main")
    .withModuleID("main2")
)

esbuildBundleHtmlEntryPoints := Seq(
  Paths.get("index1.html"),
  Paths.get("index2.html")
)

// Suppress meaningless 'multiple main classes detected' warning
Compile / mainClass := None
