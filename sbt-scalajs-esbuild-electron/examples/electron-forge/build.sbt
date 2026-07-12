import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scalajs.esbuild.electron.EsbuildElectronProcessConfiguration
import scala.sys.process._

enablePlugins(ScalaJSEsbuildElectronPlugin)

ThisBuild / scalaVersion := "2.13.18"

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

libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.1"

val esbuildElectronForgePackage =
  taskKey[Unit]("Generate package directory with Electron Forge")
val esbuildElectronForgeMakeDistributable =
  taskKey[Unit]("Package in distributable format with Electron Forge")

val perConfigSettings = Seq(Stage.FastOpt, Stage.FullOpt).flatMap { stage =>
  val stageTask = stage match {
    case Stage.FastOpt => fastLinkJS
    case Stage.FullOpt => fullLinkJS
  }

  def fn(args: List[String]) = Def.task {
    val log = streams.value.log

    (stageTask / esbuildBundle).value

    val targetDir = (esbuildStage / crossTarget).value

    val exitValue = Process(
      "node" :: "node_modules/@electron-forge/cli/dist/electron-forge.js" :: args,
      targetDir
    ).run(log).exitValue()
    if (exitValue != 0) {
      sys.error(s"Nonzero exit value: $exitValue")
    } else ()
  }

  Seq(
    stageTask / esbuildElectronForgePackage := fn("package" :: Nil).value,
    stageTask / esbuildElectronForgeMakeDistributable := fn("make" :: Nil).value
  )
}
inConfig(Compile)(perConfigSettings)
inConfig(Test)(perConfigSettings)
