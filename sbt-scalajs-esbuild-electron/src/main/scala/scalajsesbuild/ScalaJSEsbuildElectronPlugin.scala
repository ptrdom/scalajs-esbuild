package scalajsesbuild

import org.scalajs.sbtplugin.Stage
import sbt.*
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundleEntryPoints

object ScalaJSEsbuildElectronPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaJSEsbuildWebPlugin

  object autoImport {}

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[?]] =
    perScalaJSStageSettings(Stage.FastOpt) ++
      perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[?]] = {
    val stageTask = stage.stageTask

    Seq(
      stageTask / esbuildBundleEntryPoints := {
        val entryPoints = (stageTask / esbuildBundleEntryPoints).value
        entryPoints
          .filter(entryPoint =>
            !Set("main", "preload").exists(entryPoint.contains)
          )
      }
    )
  }
}
