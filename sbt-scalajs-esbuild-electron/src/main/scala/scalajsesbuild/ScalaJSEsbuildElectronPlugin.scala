package scalajsesbuild

import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSModuleInitializers
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import sbt.*
import sbt.AutoPlugin
import sbt.Keys.*
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundleScript
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildInstall
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildScalaJSModuleConfigurations
import scalajsesbuild.ScalaJSEsbuildWebPlugin.autoImport.esbuildBundleHtmlEntryPoints
import scalajsesbuild.ScalaJSEsbuildWebPlugin.autoImport.esbuildServeScript
import scalajsesbuild.ScalaJSEsbuildWebPlugin.autoImport.esbuildServeStart
import scalajsesbuild.electron.*

import scala.sys.process.Process

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
        _.withModuleKind(
          ModuleKind.ESModule
        ) // TODO try using CommonJSModule - need to figure out how to disable Closure compiler
      },
      esbuildElectronProcessConfiguration := {
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
    jsEnvInput := jsEnvInputTask.value,
    run := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task {
        (stageTask / esbuildBundle).value

        val stageTaskReport = stageTask.value.data
        val mainModule = resolveMainModule(stageTaskReport)

        val targetDirectory = (esbuildInstall / crossTarget).value
        val outputDirectory =
          (stageTask / esbuildBundle / crossTarget).value
        val path =
          targetDirectory
            .relativize(new File(outputDirectory, mainModule.jsFileName))
            .getOrElse(
              sys.error(
                s"Target directory [$targetDirectory] must be parent directory of output directory [$outputDirectory]"
              )
            )

        val exitValue = Process(
          "node" :: "./node_modules/electron/cli" :: path.toString :: Nil,
          targetDirectory
        ).run(streams.value.log)
          .exitValue()
        if (exitValue != 0) {
          sys.error(s"Nonzero exit value: $exitValue")
        }
      }
    }.value
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[?]] = {
    val stageTask = stage.stageTask

    Seq(
      stageTask / esbuildBundleScript := {
        val configurationV = configuration.value
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

        val minify = if (configurationV == Test) {
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
          |  {external: ['electron']}
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
        val configurationV = configuration.value
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
        val mainModuleEntryPointJs = s"'$mainModuleEntryPoint'"
        val preloadModuleEntryPointsJs =
          preloadModuleEntryPoints
            .map("'" + _ + "'")
            .mkString("[", ",", "]")
        val rendererModuleEntryPointsJsArray = rendererModuleEntryPoints
          .map("'" + _ + "'")
          .mkString("[", ",", "]")
        val targetDirectory = (esbuildInstall / crossTarget).value
        val nodeRelativeOutputDirectoryJs = {
          val outputDirectory =
            (stageTask / esbuildBundle / crossTarget).value
          val nodeRelativeOutputDirectory = targetDirectory
            .relativize(outputDirectory)
            .getOrElse(
              sys.error(
                s"Target directory [$targetDirectory] must be parent directory of node output directory [$outputDirectory]"
              )
            )
          s"'$nodeRelativeOutputDirectory'"
        }
        val rendererRelativeOutputDirectoryJs = {
          val outputDirectory =
            (stageTask / esbuildServeStart / crossTarget).value
          val relativeOutputDirectory =
            targetDirectory
              .relativize(outputDirectory)
              .getOrElse(
                sys.error(
                  s"Target directory [$targetDirectory] must be parent directory of renderer output directory [$outputDirectory]"
                )
              )
          s"'$relativeOutputDirectory'"
        }
        val htmlEntryPoints = esbuildBundleHtmlEntryPoints.value
        require(
          !htmlEntryPoints.forall(_.isAbsolute),
          "HTML entry point paths must be relative"
        )
        val htmlEntryPointsJsArray =
          htmlEntryPoints.map("'" + _ + "'").mkString("[", ",", "]")

        // language=JS
        s"""
           |${EsbuildScripts.esbuildOptions}
           |
           |${EsbuildScripts.bundle}
           |
           |${EsbuildWebScripts.htmlTransform}
           |
           |${EsbuildWebScripts.esbuildLiveReload}
           |
           |${EsbuildWebScripts.serve}
           |
           |${electron.Scripts.electronServe}
           |
           |serve(
           |  $rendererModuleEntryPointsJsArray,
           |  $rendererRelativeOutputDirectoryJs,
           |  'assets',
           |  'sbt-scalajs-esbuild-renderer-bundle-meta.json',
           |  8001,
           |  8000,
           |  $htmlEntryPointsJsArray
           |)
           |  .then((reloadEventEmitter) => {
           |    electronServe(
           |      reloadEventEmitter,
           |      8000,
           |      $mainModuleEntryPointJs,
           |      $preloadModuleEntryPointsJs,
           |      $nodeRelativeOutputDirectoryJs
           |    );
           |  });
           |""".stripMargin
      }
    )
  }
}