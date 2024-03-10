import java.nio.file.Path

enablePlugins(ScalaJSEsbuildWebPlugin)

scalaVersion := "2.13.13"

libraryDependencies ++= Seq(
  "org.scala-js" %%% "scalajs-dom" % "2.2.0",
  "org.scalatest" %%% "scalatest" % "3.2.16" % "test"
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
  Path.of("index1.html"),
  Path.of("index2.html")
)

// Suppress meaningless 'multiple main classes detected' warning
Compile / mainClass := None
