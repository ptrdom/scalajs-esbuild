import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scala.sys.process._

enablePlugins(ScalaJSEsbuildElectronPlugin)

scalaVersion := "2.13.8"

scalaJSModuleInitializers := Seq(
  ModuleInitializer
    .mainMethodWithArgs("example.Main", "main")
    .withModuleID("main"),
  ModuleInitializer
    .mainMethodWithArgs("example.Preload", "main")
    .withModuleID("preload"),
  ModuleInitializer
    .mainMethodWithArgs("example.Renderer", "main")
    .withModuleID("renderer")
)

Compile / esbuildElectronProcessConfiguration := new scalajsesbuild.EsbuildElectronProcessConfiguration(
  "main",
  Set("preload"),
  Set("renderer")
)

// Suppress meaningless 'multiple main classes detected' warning
Compile / mainClass := None

libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.2.0"

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
