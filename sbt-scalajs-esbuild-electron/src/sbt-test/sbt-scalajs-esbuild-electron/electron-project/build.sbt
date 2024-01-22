import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scala.sys.process._

lazy val root = (project in file("."))
  .aggregate(app, `integration-test`)

ThisBuild / scalaVersion := "2.13.8"

lazy val commonSettings = Seq(
  esbuildResourcesDirectory := baseDirectory.value / ".." / "esbuild"
)

lazy val app = (project in file("app"))
  .enablePlugins(ScalaJSEsbuildElectronPlugin)
  .settings(
    commonSettings,
    // Suppress meaningless 'multiple main classes detected' warning
    Compile / mainClass := None,
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
    ),
    Compile / esbuildElectronProcessConfiguration := new scalajsesbuild.EsbuildElectronProcessConfiguration(
      "main",
      Set("preload"),
      Set("renderer")
    ),
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.2.0"
  )

lazy val `integration-test` = (project in file("integration-test"))
  .enablePlugins(ScalaJSEsbuildPlugin)
  .settings(
    commonSettings,
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.CommonJSModule)
    },
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
  )
