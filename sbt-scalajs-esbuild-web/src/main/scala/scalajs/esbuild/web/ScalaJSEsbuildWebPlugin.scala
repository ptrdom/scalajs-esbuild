package scalajs.esbuild.web

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

import org.scalajs.jsenv.Input.Script
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import sbt.*
import sbt.AutoPlugin
import sbt.Keys.*
import sbt.nio.Keys.watchBeforeCommand
import sbt.nio.Keys.watchOnTermination
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundleScript
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildStage
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildRunner
import scalajs.esbuild.Scripts as BaseScripts
import scalajs.esbuild.{Scripts as _, *}

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
    val esbuildServeStart: TaskKey[Unit] =
      taskKey[Unit]("Starts running esbuild serve on target directory")
    val esbuildServeStop: TaskKey[Unit] =
      taskKey[Unit]("Stops running esbuild serve on target directory")
    val esbuildServe: TaskKey[Unit] =
      taskKey[Unit]("Runs esbuild serve on target directory")
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] =
    Seq(
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
      },
      esbuildBundleHtmlEntryPoints := Seq(
        Paths.get("index.html")
      )
    ) ++ inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[?]] = Seq(
    jsEnvInput := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task {
        (stageTask / esbuildBundle).value

        val targetDir = (stageTask / esbuildStage / crossTarget).value

        val bundleOutputs = IO
          .readLines(targetDir / "sbt-scalajs-esbuild-bundle-output.txt")
          .map(_.split(File.pathSeparator).toList match {
            case List(entryPoint, output) => entryPoint -> output
            case invalid                  =>
              sys.error(s"Invalid bundle output line format [$invalid]")
          })
          .toMap

        val mainModule = resolveMainModule(stageTask.value.data)

        val outputBundle = bundleOutputs.getOrElse(
          mainModule.jsFileName,
          sys.error(
            s"Output bundle for main module entryPoint [${mainModule.jsFileName}] not found in bundle outputs [$bundleOutputs]"
          )
        )

        Seq(
          Script(
            ((stageTask / esbuildStage / crossTarget).value / outputBundle).toPath
          )
        )
      }
    }.value,
    esbuildServe / serverPort := 3000,
    esbuildServeStart := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task((stageTask / esbuildServeStart).value)
    }.value,
    esbuildServeStop := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task((stageTask / esbuildServeStop).value)
    }.value,
    esbuildServe := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task((stageTask / esbuildServe).value)
    }.value,
    esbuildServe / watchBeforeCommand := Def.settingDyn {
      val stageTask = scalaJSStage.value.stageTask
      stageTask / (esbuildServe / watchBeforeCommand)
    }.value,
    esbuildServe / watchOnTermination := Def.settingDyn {
      val stageTask = scalaJSStage.value.stageTask
      stageTask / (esbuildServe / watchOnTermination)
    }.value
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[?]] = {
    val stageTask = stage.stageTask

    Seq(
      stageTask / esbuildBundleScript := {
        val stageTaskReport = stageTask.value.data
        val entryPoints = jsFileNames(stageTaskReport)
        val entryPointsJsArray =
          entryPoints.map("'" + _ + "'").mkString("[", ",", "]")
        val targetDirectory = (esbuildStage / crossTarget).value
        val outputDirectory =
          (esbuildBundle / crossTarget).value
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
           |${BaseScripts.esbuildOptions}
           |
           |${BaseScripts.bundle}
           |
           |${Scripts.htmlTransform}
           |
           |${Scripts.transformHtmlEntryPoints}
           |
           |const metaFilePromise = bundle(
           |  ${EsbuildPlatform.Browser.jsValue},
           |  $entryPointsJsArray,
           |  $relativeOutputDirectoryJs,
           |  'assets',
           |  $hashOutputFiles,
           |  $minify
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
      stageTask / esbuildServe / crossTarget := (esbuildStage / crossTarget).value / "www",
      stageTask / esbuildServe / serverPort := (esbuildServe / serverPort).value,
      stageTask / esbuildServeScript := {
        val stageTaskReport = stageTask.value.data
        val entryPoints = jsFileNames(stageTaskReport)
        val entryPointsJsArray =
          entryPoints.map("'" + _ + "'").mkString("[", ",", "]")
        val targetDirectory = (esbuildStage / crossTarget).value
        val outputDirectory =
          (stageTask / esbuildServe / crossTarget).value
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
        val servePort = (esbuildServe / serverPort).value

        // language=JS
        s"""
           |${BaseScripts.esbuildOptions}
           |
           |${BaseScripts.bundle}
           |
           |${Scripts.htmlTransform}
           |
           |${Scripts.esbuildLiveReload}
           |
           |${Scripts.serve}
           |
           |serve(
           |  $entryPointsJsArray,
           |  $relativeOutputDirectoryJs,
           |  'assets',
           |  0,
           |  $servePort,
           |  $htmlEntryPointsJsArray
           |);
           |""".stripMargin
      }
    ) ++ {
      var process: Option[Process] = None

      val terminateProcess = (log: Logger) => {
        process.foreach { process =>
          log.info(s"Stopping esbuild serve process")

          // using reflection to keep JDK 8 compatibility
          val javaProcessMethod = process.getClass.getDeclaredField("p")
          javaProcessMethod.setAccessible(true)
          val javaProcess =
            javaProcessMethod.get(process).asInstanceOf[java.lang.Process]
          javaProcess.getClass.getMethods
            .find(
              _.getName == "descendants"
            )
            .foreach { descendantsMethod =>
              val descendants = descendantsMethod
                .invoke(javaProcess)
                .asInstanceOf[java.util.stream.Stream[Any]]
              descendants.forEach { process =>
                process.getClass.getInterfaces
                  .find(_.getName == "java.lang.ProcessHandle")
                  .foreach { processHandle =>
                    val destroyMethod =
                      processHandle.getDeclaredMethod("destroy")
                    destroyMethod.invoke(process)
                  }
              }
            }

          process.destroy()
        }
        process = None
      }

      Seq(
        stageTask / esbuildServeStart := {
          val logger = state.value.globalLogging.full

          (stageTask / esbuildServeStop).value

          (stageTask / esbuildStage).value

          val targetDir = (esbuildStage / crossTarget).value

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
      ) ++ {
        var watch: Boolean = false

        Seq(
          stageTask / esbuildServe := {
            val logger = state.value.globalLogging.full

            (stageTask / esbuildStage).value

            val targetDir = (esbuildStage / crossTarget).value

            val script = (stageTask / esbuildServeScript).value

            if (!watch) {
              terminateProcess(logger)
            }

            val p = process.getOrElse {
              logger.info(s"Starting esbuild serve process")
              val scriptFileName = "sbt-scalajs-esbuild-serve-script.cjs"
              IO.write(targetDir / scriptFileName, script)

              val p =
                esbuildRunner.value.process(logger)(scriptFileName, targetDir)
              process = Some(p)
              p
            }

            if (!watch) {
              val exitValue = p.exitValue()
              if (exitValue != 0) {
                scala.sys.error("Nonzero exit value: " + exitValue)
              }
            }
          },
          stageTask / (esbuildServe / watchBeforeCommand) := { () =>
            {
              if (!watch) {
                watch = true
                terminateProcess(Keys.sLog.value)
              }
            }
          },
          stageTask / (esbuildServe / watchOnTermination) := { (_, _, _, s) =>
            terminateProcess(Keys.sLog.value)
            watch = false
            s
          }
        )
      }
    }
  }
}
