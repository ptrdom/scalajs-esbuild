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

val viteElectronBuildPackage =
  taskKey[Unit]("Generate package directory with electron-builder")
val viteElectronBuildDistributable =
  taskKey[Unit]("Package in distributable format with electron-builder")

val perConfigSettings = Seq(Stage.FastOpt, Stage.FullOpt).flatMap { stage =>
  val stageTask = stage match {
    case Stage.FastOpt => fastLinkJS
    case Stage.FullOpt => fullLinkJS
  }

  def fn(args: List[String] = Nil) = Def.task {
    val log = streams.value.log

    (stageTask / esbuildBundle).value

    val targetDir = (esbuildStage / crossTarget).value

    val exitValue = Process(
      "node" :: "node_modules/electron-builder/cli" :: Nil ::: args,
      targetDir
    ).run(log).exitValue()
    if (exitValue != 0) {
      sys.error(s"Nonzero exit value: $exitValue")
    } else ()
  }

  Seq(
    stageTask / viteElectronBuildPackage := fn("--dir" :: Nil).value,
    stageTask / viteElectronBuildDistributable := fn().value
  )
}
inConfig(Compile)(perConfigSettings)
inConfig(Test)(perConfigSettings)
