package scalajsesbuild

import java.nio.file.Path

import org.scalajs.jsenv.Input.Script
import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.linker.interface.unstable.ModuleInitializerImpl
import org.scalajs.linker.interface.unstable.ReportImpl
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSMainModuleInitializer
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSModuleInitializers
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import org.scalajs.testing.adapter.TestAdapterInitializer
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
    scalaJSModuleInitializers := {
      scalaJSModuleInitializers.value
        .map { moduleInitializer =>
          moduleInitializer.initializer match {
            case test
                if test == ModuleInitializer
                  .mainMethod(
                    TestAdapterInitializer.ModuleClassName,
                    TestAdapterInitializer.MainMethodName
                  )
                  .initializer =>
              moduleInitializer.withModuleID("test-main")
            case _ =>
              moduleInitializer
          }
        }
    },
    jsEnvInput := jsEnvInputTask.value,
    run := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task {
        (stageTask / esbuildBundle).value

        val configurationV = configuration.value
        val stageTaskReport = stageTask.value.data
        val mainModule = resolveMainModule(configurationV, stageTaskReport)

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
        val mainModule = resolveMainModule(configurationV, stageTaskReport)
        val nodeEntryPointsJsArray =
          (preloadModuleEntryPoints + mainModuleEntryPoint + mainModule.jsFileName)
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
        ???
      }
    )
  }
}
