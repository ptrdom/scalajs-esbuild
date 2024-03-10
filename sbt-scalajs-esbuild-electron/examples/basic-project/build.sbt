import org.scalajs.linker.interface.ModuleInitializer
import scalajsesbuild.EsbuildElectronProcessConfiguration

enablePlugins(ScalaJSEsbuildElectronPlugin)

ThisBuild / scalaVersion := "2.13.13"

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

Compile / esbuildElectronProcessConfiguration := new EsbuildElectronProcessConfiguration(
  "main",
  Set("preload"),
  Set("renderer")
)

// Suppress meaningless 'multiple main classes detected' warning
Compile / mainClass := None

libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0"
