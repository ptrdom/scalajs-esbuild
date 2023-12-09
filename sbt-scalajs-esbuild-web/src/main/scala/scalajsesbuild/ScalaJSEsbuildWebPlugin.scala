package scalajsesbuild

import org.scalajs.jsenv.Input.Script
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import org.typelevel.jawn.ast.JObject
import org.typelevel.jawn.ast.JParser
import sbt.AutoPlugin
import sbt.*
import sbt.Keys.*
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundleScript
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildCompile
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildInstall
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildRunner
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildScalaJSModuleConfigurations
import java.nio.file.Path

import scala.sys.process.*

object ScalaJSEsbuildWebPlugin extends AutoPlugin {

  override def requires = ScalaJSEsbuildPlugin

  object autoImport {
    val esbuildBundleHtmlEntryPoints: TaskKey[Seq[Path]] = taskKey(
      "HTML entry points to be injected and transformed during esbuild bundling"
    )
    val esbuildServeScript: TaskKey[String] = taskKey(
      "esbuild script used for serving"
    ) // TODO consider doing the writing of the script upon call of this task, then use FileChanges to track changes to the script
    val esbuildServeStart =
      taskKey[Unit]("Runs esbuild serve on target directory")
    val esbuildServeStop =
      taskKey[Unit]("Stops running esbuild serve on target directory")
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] =
    Seq(
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
      },
      esbuildBundleHtmlEntryPoints := Seq(
        Path.of("index.html")
      )
    ) ++ inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[?]] = Seq(
    jsEnvInput := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task {
        (stageTask / esbuildBundle).value

        val targetDir = (stageTask / esbuildInstall / crossTarget).value

        val metaJson =
          JParser.parseUnsafe(
            IO.read(targetDir / "sbt-scalajs-esbuild-bundle-meta.json")
          )

        jsFileNames(stageTask.value.data)
          .map { jsFileName =>
            metaJson
              .get("outputs")
              .asInstanceOf[JObject]
              .vs
              .collectFirst {
                case (outputBundle, output)
                    if output
                      .asInstanceOf[JObject]
                      .get("entryPoint")
                      .getString
                      .contains(jsFileName) =>
                  outputBundle
              }
              .getOrElse(
                sys.error(s"Output bundle not found for module [$jsFileName]")
              )
          }
          .map((stageTask / esbuildInstall / crossTarget).value / _)
          .map(_.toPath)
          .map(Script)
          .toSeq
      }
    }.value
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[?]] = {
    val stageTask = stage.stageTask

    Seq(
      stageTask / esbuildBundleScript := {
        val stageTaskReport = stageTask.value.data
        val moduleConfigurations = esbuildScalaJSModuleConfigurations.value
        val entryPoints =
          extractEntryPointsByPlatform(stageTaskReport, moduleConfigurations)
            .getOrElse(
              EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Browser,
              Set.empty
            )
        val entryPointsJsArray =
          entryPoints.map("'" + _ + "'").mkString("[", ",", "]")
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

        val (hashOutputFiles, minify) = if (configuration.value == Test) {
          (false, false)
        } else {
          (true, true)
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
           |const metaFilePromise = bundle(
           |  ${EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Browser.jsValue},
           |  $entryPointsJsArray,
           |  $relativeOutputDirectoryJs,
           |  'assets',
           |  $hashOutputFiles,
           |  $minify,
           |  'sbt-scalajs-esbuild-bundle-meta.json'
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
        val stageTaskReport = stageTask.value.data
        val moduleConfigurations = esbuildScalaJSModuleConfigurations.value
        val entryPoints =
          extractEntryPointsByPlatform(stageTaskReport, moduleConfigurations)
            .getOrElse(
              EsbuildScalaJSModuleConfiguration.EsbuildPlatform.Browser,
              Set.empty
            )
        val entryPointsJsArray =
          entryPoints.map("'" + _ + "'").mkString("[", ",", "]")
        val targetDirectory = (esbuildInstall / crossTarget).value
        val outputDirectory =
          (stageTask / esbuildServeStart / crossTarget).value
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
           |serve(
           |  $entryPointsJsArray,
           |  $relativeOutputDirectoryJs,
           |  'assets',
           |  'sbt-scalajs-esbuild-serve-meta.json',
           |  8001,
           |  8000,
           |  $htmlEntryPointsJsArray
           |);
           |""".stripMargin
      }
    ) ++ {
      var process: Option[Process] = None

      val terminateProcess = (log: Logger) => {
        process.foreach { process =>
          log.info(s"Stopping esbuild serve process")
          process.destroy()
        }
        process = None
      }

      Seq(
        stageTask / esbuildServeStart / crossTarget := (esbuildInstall / crossTarget).value / "www",
        stageTask / esbuildServeStart := {
          val logger = state.value.globalLogging.full

          (stageTask / esbuildServeStop).value

          (stageTask / esbuildCompile).value

          val targetDir = (esbuildInstall / crossTarget).value

          val script = (stageTask / esbuildServeScript).value

          logger.info(s"Starting esbuild serve process")
          val scriptFileName = "sbt-scalajs-esbuild-serve-script.cjs"
          IO.write(targetDir / scriptFileName, script)

          process =
            Some(esbuildRunner.value.process(logger)(scriptFileName, targetDir))
        },
        stageTask / esbuildServeStop := {
          terminateProcess(streams.value.log)
        },
        (onLoad in Global) := {
          (onLoad in Global).value.compose(
            _.addExitHook {
              terminateProcess(Keys.sLog.value)
            }
          )
        }
      )
    }
  }
}
