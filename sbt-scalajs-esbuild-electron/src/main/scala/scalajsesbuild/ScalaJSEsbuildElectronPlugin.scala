package scalajsesbuild

import java.nio.file.Path

import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSModuleInitializers
import sbt.AutoPlugin
import sbt.Plugins
import sbt.*
import sbt.Keys.*
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildScalaJSModuleConfigurations
import scalajsesbuild.ScalaJSEsbuildWebPlugin.autoImport.esbuildBundleHtmlEntryPoints

object ScalaJSEsbuildElectronPlugin extends AutoPlugin {

  override def requires = ScalaJSEsbuildWebPlugin

  object autoImport {
    val esbuildElectronRendererScalaJSModules: TaskKey[Set[String]] = taskKey(
      "Scala.js modules implementing electron renderers"
    )
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] =
    Seq(
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.CommonJSModule)
      }
    ) ++ inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[?]] = Seq(
    esbuildElectronRendererScalaJSModules := Set.empty,
    esbuildScalaJSModuleConfigurations := {
      val electronRendererModules =
        esbuildElectronRendererScalaJSModules.value
      val modules = scalaJSModuleInitializers.value
      modules
        .map(module =>
          module.moduleID ->
            new EsbuildScalaJSModuleConfiguration(
              if (electronRendererModules.contains(module.moduleID)) {
                EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Browser
              } else {
                EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Node
              }
            )
        )
        .toMap
    }
  )
}
