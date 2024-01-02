package scalajsesbuild

import java.nio.file.Path

import org.scalajs.jsenv.Input.Script
import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.linker.interface.unstable.ReportImpl
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSModuleInitializers
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import sbt.AutoPlugin
import sbt.Plugins
import sbt.*
import sbt.Keys.*
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundleScript
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildInstall
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildScalaJSModuleConfigurations
import scalajsesbuild.ScalaJSEsbuildWebPlugin.autoImport.esbuildBundleHtmlEntryPoints
import scalajsesbuild.ScalaJSEsbuildWebPlugin.autoImport.esbuildServeScript
import scalajsesbuild.electron.*

object ScalaJSEsbuildElectronPlugin extends AutoPlugin {

  override def requires = ScalaJSEsbuildWebPlugin

  object autoImport {
    // TODO need to replace this with electron process config - not needed for bundling, but needed for serving
    val esbuildElectronProcessConfiguration
        : TaskKey[EsbuildElectronProcessConfiguration] = taskKey(
      "Configuration linking Scala.js modules to Electron process module components"
    )
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] =
    Seq(
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.CommonJSModule)
      },
      esbuildElectronProcessConfiguration := {
        // TODO need to deal with main module name collision with org.scalajs.ir.Names.DefaultModuleID

        val modules: Seq[ModuleInitializer] = scalaJSModuleInitializers.value
        (modules.headOption, modules.tail.isEmpty) match {
          case (Some(module), true) =>
            EsbuildElectronProcessConfiguration.main(module.moduleID)
          case _ =>
            sys.error(
              "Unable to automatically derive `esbuildElectronProcessConfiguration`, the settings needs to be provided manually"
            )
        }
      }
    ) ++ inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[?]] = Seq(
    esbuildScalaJSModuleConfigurations := Map.empty,
    jsEnvInput := jsEnvInputTask.value
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[?]] = {
    val stageTask = stage.stageTask

    Seq(
      stageTask / esbuildBundleScript := {
        val stageTaskReport = stageTask.value.data
        val electronProcessConfiguration =
          esbuildElectronProcessConfiguration.value
        val (
          mainModuleEntryPoint,
          preloadModuleEntryPoints,
          rendererModuleEntryPoints
        ) = extractEntryPointsByProcess(
          stageTaskReport,
          electronProcessConfiguration
        )
        val nodeEntryPointsJsArray =
          (preloadModuleEntryPoints + mainModuleEntryPoint)
            .map("'" + _ + "'")
            .mkString("[", ",", "]")
        val rendererModuleEntryPointsJsArray = rendererModuleEntryPoints
          .map("'" + _ + "'")
          .mkString("[", ",", "]")
        val targetDirectory = (esbuildInstall / crossTarget).value
        val outputDirectory =
          (stageTask / esbuildBundle / crossTarget).value
        val relativeOutputDirectory =
          targetDirectory
            .relativize(outputDirectory)
            .getOrElse(
              sys.error(
                s"Target directory [$targetDirectory] must be parent directory of output directory [$outputDirectory]"
              )
            )
        val relativeOutputDirectoryJs = s"'$relativeOutputDirectory'"
        val htmlEntryPoints = esbuildBundleHtmlEntryPoints.value
        require(
          !htmlEntryPoints.forall(_.isAbsolute),
          "HTML entry point paths must be relative"
        )
        val htmlEntryPointsJsArray =
          htmlEntryPoints.map("'" + _ + "'").mkString("[", ",", "]")

        val minify = if (configuration.value == Test) {
          false
        } else {
          true
        }

        // language=JS
        s"""
          |${EsbuildScripts.esbuildOptions}
          |
          |${EsbuildScripts.bundle}
          |
          |${EsbuildWebScripts.htmlTransform}
          |
          |${EsbuildWebScripts.transformHtmlEntryPoints}
          |
          |bundle(
          |  ${EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Node.jsValue},
          |  $nodeEntryPointsJsArray,
          |  $relativeOutputDirectoryJs,
          |  null,
          |  false,
          |  $minify,
          |  'sbt-scalajs-esbuild-node-bundle-meta.json',
          |  {
          |    external: ['electron']
          |  }
          |);
          |
          |const metaFilePromise = bundle(
          |  ${EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Browser.jsValue},
          |  $rendererModuleEntryPointsJsArray,
          |  $relativeOutputDirectoryJs,
          |  'assets',
          |  false,
          |  $minify,
          |  'sbt-scalajs-esbuild-renderer-bundle-meta.json'
          |);
          |
          |metaFilePromise
          |  .then((metaFile) => {
          |      transformHtmlEntryPoints(
          |        $htmlEntryPointsJsArray,
          |        $relativeOutputDirectoryJs,
          |        metaFile
          |      );
          |  });
          |""".stripMargin
      },
      stageTask / esbuildServeScript := {
        ???
      }
    )
  }
}
