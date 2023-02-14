package scalajsesbuild

import java.nio.file.Files

import org.scalajs.jsenv.Input.Script
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerOutputDirectory
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import sbt._
import sbt.AutoPlugin
import sbt.Keys._

import scala.jdk.CollectionConverters._
import scala.sys.process._

object ScalaJSEsbuildPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaJSPlugin

  object autoImport {
    val esbuildRunner: SettingKey[EsbuildRunner] =
      settingKey("Runs Esbuild commands")
    val esbuildPackageManager: SettingKey[PackageManager] =
      settingKey("Package manager to use for esbuild tasks")
    val esbuildResourcesDirectory: SettingKey[sbt.File] =
      settingKey("esbuild resource directory")
    val esbuildCopyResources: TaskKey[ChangeStatus] =
      taskKey("Copies over esbuild resources to target directory")
    val esbuildInstall: TaskKey[ChangeStatus] =
      taskKey(
        "Runs package manager's `install` in target directory on copied over esbuild resources"
      )
    val esbuildCompile: TaskKey[ChangeStatus] =
      taskKey(
        "Compiles module and copies output to target directory"
      )
    val esbuildBundleScript: TaskKey[String] = taskKey(
      "esbuild script used for bundling"
    )
    val esbuildBundle: TaskKey[ChangeStatus] = taskKey(
      "Bundles module with esbuild"
    )
    val esbuildServeStart =
      taskKey[Unit]("Runs esbuild serve on target directory")
    val esbuildServeStop =
      taskKey[Unit]("Stops running esbuild serve on target directory")
  }

  import autoImport._

  sealed trait ChangeStatus
  object ChangeStatus {
    case object Pristine extends ChangeStatus
    case object Dirty extends ChangeStatus

    implicit class ChangeStatusOps(changeStatus: ChangeStatus) {
      def combine(other: ChangeStatus): ChangeStatus = {
        (changeStatus, other) match {
          case (Pristine, Pristine) =>
            Pristine
          case _ => Dirty
        }
      }
    }
  }

  sealed trait Stage
  object Stage {
    case object FullOpt extends Stage
    case object FastOpt extends Stage
  }

  // TODO rework with https://www.scala-sbt.org/1.x/docs/Howto-Track-File-Inputs-and-Outputs.html
  private def copyChanges(
      logger: Logger
  )(
      sourceDirectory: File,
      targetDirectory: File,
      currentDirectory: File
  ): ChangeStatus = {
    logger.debug(s"Walking directory [${currentDirectory.getAbsolutePath}]")
    Files
      .walk(currentDirectory.toPath)
      .iterator()
      .asScala
      .map(_.toFile)
      .filter(file => file.getAbsolutePath != currentDirectory.getAbsolutePath)
      .foldLeft[ChangeStatus](ChangeStatus.Pristine) {
        case (changeStatus, file) =>
          if (file.isDirectory) {
            copyChanges(logger)(sourceDirectory, targetDirectory, file)
          } else {
            val targetFile = new File(
              file.getAbsolutePath.replace(
                sourceDirectory.getAbsolutePath,
                targetDirectory.getAbsolutePath
              )
            )
            if (!Hash(file).sameElements(Hash(targetFile))) {
              logger.debug(
                s"File changed [${file.getAbsolutePath}], copying to [${targetFile.getAbsolutePath}]"
              )
              IO.copyFile(
                file,
                targetFile
              )
              ChangeStatus.Dirty
            } else {
              logger.debug(s"File not changed [${file.getAbsolutePath}]")
              changeStatus
            }
          }
      }
  }

  private def jsFileNames(report: Report) = {
    report match {
      case report: unstable.ReportImpl =>
        val jsFileNames = report.publicModules
          .map { publicModule =>
            publicModule.jsFileName
          }
        jsFileNames
      case unhandled =>
        sys.error(s"Unhandled report type [$unhandled]")
    }
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
    },
    esbuildRunner := EsbuildRunner.Default,
    esbuildResourcesDirectory := baseDirectory.value / "esbuild",
    esbuildPackageManager := PackageManager.Npm
  ) ++
    inConfig(Compile)(perConfigSettings) ++
    inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[_]] = Seq(
    unmanagedSourceDirectories += esbuildResourcesDirectory.value,
    esbuildInstall / crossTarget := {
      crossTarget.value /
        "esbuild" /
        (if (configuration.value == Compile) "main" else "test")
    },
    esbuildCopyResources := {
      val s = streams.value

      val targetDir = (esbuildInstall / crossTarget).value

      copyChanges(s.log)(
        esbuildResourcesDirectory.value,
        targetDir,
        esbuildResourcesDirectory.value
      )
    },
    watchSources := (watchSources.value ++ Seq(
      Watched.WatchSource(esbuildResourcesDirectory.value)
    )),
    esbuildInstall := {
      val changeStatus = esbuildCopyResources.value

      val s = streams.value

      val targetDir = (esbuildInstall / crossTarget).value

      val lockFile = esbuildPackageManager.value.lockFile

      FileFunction.cached(
        streams.value.cacheDirectory /
          "esbuild" /
          (if (configuration.value == Compile) "main" else "test"),
        inStyle = FilesInfo.hash
      ) { _ =>
        s.log.debug(s"Installing packages")

        esbuildPackageManager.value.install(s.log)(targetDir)

        IO.copyFile(
          targetDir / lockFile,
          esbuildResourcesDirectory.value / lockFile
        )

        Set.empty
      }(
        Set(
          esbuildResourcesDirectory.value / "package.json",
          esbuildResourcesDirectory.value / lockFile
        )
      )

      changeStatus
    },
    jsEnvInput := Def.taskDyn {
      val stageTask = scalaJSStage.value match {
        case org.scalajs.sbtplugin.Stage.FastOpt => fastLinkJS
        case org.scalajs.sbtplugin.Stage.FullOpt => fullLinkJS
      }
      Def.task {
        (stageTask / esbuildBundle).value

        jsFileNames(stageTask.value.data)
          .map((stageTask / esbuildBundle / crossTarget).value / _)
          .map(_.toPath)
          .map(Script)
          .toSeq
      }
    }.value
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val stageTask = stage match {
      case Stage.FastOpt => fastLinkJS
      case Stage.FullOpt => fullLinkJS
    }

    Seq(
      stageTask / esbuildCompile := {
        val changeStatus = esbuildInstall.value

        val targetDir = (esbuildInstall / crossTarget).value

        stageTask.value

        copyChanges(streams.value.log)(
          (stageTask / scalaJSLinkerOutputDirectory).value,
          targetDir,
          (stageTask / scalaJSLinkerOutputDirectory).value
        ).combine(changeStatus)
      },
      stageTask / esbuildBundle / crossTarget := (esbuildInstall / crossTarget).value / "out",
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value

        val stageTaskResult = stageTask.value

        val entryPoints = jsFileNames(stageTaskResult.data)
          .map(jsFileName =>
            s"'${(targetDir / jsFileName).absolutePath.replace("\\", "\\\\")}'"
          )
          .mkString(",")
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value.absolutePath
            .replace("\\", "\\\\")
        // taken from Vite's KNOWN_ASSET_TYPES constant
        val loaders = Seq(
          // images
          "png",
          "jpe?g",
          "jfif",
          "pjpeg",
          "pjp",
          "gif",
          "svg",
          "ico",
          "webp",
          "avif",

          // media
          "mp4",
          "webm",
          "ogg",
          "mp3",
          "wav",
          "flac",
          "aac",

          // fonts
          "woff2?",
          "eot",
          "ttf",
          "otf",

          // other
          "webmanifest",
          "pdf",
          "txt"
        ).map(assetType => s"'.$assetType': 'file'")
          .mkString(",")
        s"""
             |const esbuild = require("esbuild");
             |
             |const bundle = async () => {
             |  await esbuild.build({
             |    entryPoints: [$entryPoints],
             |    bundle: true,
             |    outdir: '$outdir',
             |    loader: { $loaders },
             |  })
             |}
             |
             |bundle()
             |""".stripMargin
      },
      stageTask / esbuildBundle := {
        val log = streams.value.log

        val changeStatus = (stageTask / esbuildCompile).value
        val bundlingScript = (stageTask / esbuildBundleScript).value

        if (changeStatus == ChangeStatus.Dirty) {
          val targetDir = (esbuildInstall / crossTarget).value

          val scriptFileName = "sbt-scalajs-esbuild-bundle-script.cjs"
          IO.write(targetDir / scriptFileName, bundlingScript)

          esbuildRunner.value.run(log)(scriptFileName, targetDir)
        }

        changeStatus
      }
    ) ++ {
      var process: Option[Process] = None

      def terminateProcess(log: Logger) = {
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

          val entryPoints = jsFileNames(stageTask.value.data)
            .map(jsFileName =>
              s"'${(targetDir / jsFileName).absolutePath.replace("\\", "\\\\")}'"
            )
            .mkString(",")
          val outdir =
            (stageTask / esbuildServeStart / crossTarget).value.absolutePath
              .replace("\\", "\\\\")
          // taken from Vite's KNOWN_ASSET_TYPES constant
          val loaders = Seq(
            // images
            "png",
            "jpe?g",
            "jfif",
            "pjpeg",
            "pjp",
            "gif",
            "svg",
            "ico",
            "webp",
            "avif",

            // media
            "mp4",
            "webm",
            "ogg",
            "mp3",
            "wav",
            "flac",
            "aac",

            // fonts
            "woff2?",
            "eot",
            "ttf",
            "otf",

            // other
            "webmanifest",
            "pdf",
            "txt"
          ).map(assetType => s"'.$assetType': 'file'")
            .mkString(",")
          val script =
            s"""
               |const http = require("http");
               |const esbuild = require("esbuild");
               |
               |const serve = async () => {
               |    // Start esbuild's local web server. Random port will be chosen by esbuild.
               |    const ctx  = await esbuild.context({
               |        entryPoints: [$entryPoints],
               |        bundle: true,
               |        outdir: '$outdir',
               |        loader: { $loaders },
               |        logOverride: {
               |            'equals-negative-zero': 'silent',
               |        },
               |    });
               |
               |    await ctx.watch()
               |
               |    const { host, port } = await ctx.serve({
               |        servedir: '$outdir',
               |        port: 8001
               |    });
               |
               |    // Create a second (proxy) server that will forward requests to esbuild.
               |    const proxy = http.createServer((req, res) => {
               |        // forwardRequest forwards an http request through to esbuid.
               |        const forwardRequest = (path) => {
               |            const options = {
               |                hostname: host,
               |                port,
               |                path,
               |                method: req.method,
               |                headers: req.headers,
               |            };
               |
               |            const proxyReq = http.request(options, (proxyRes) => {
               |                if (proxyRes.statusCode === 404) {
               |                    // If esbuild 404s the request, assume it's a route needing to
               |                    // be handled by the JS bundle, so forward a second attempt to `/`.
               |                    return forwardRequest("/");
               |                }
               |
               |                // Otherwise esbuild handled it like a champ, so proxy the response back.
               |                res.writeHead(proxyRes.statusCode, proxyRes.headers);
               |                proxyRes.pipe(res, { end: true });
               |            });
               |
               |            req.pipe(proxyReq, { end: true });
               |        };
               |
               |        // When we're called pass the request right through to esbuild.
               |        forwardRequest(req.url);
               |    });
               |
               |    // Start our proxy server at the specified `listen` port.
               |    proxy.listen(8000);
               |
               |    console.log("Started esbuild serve process [http://localhost:8000]");
               |};
               |
               |// Serves all content from $outdir on :8000.
               |// If esbuild 404s the request, the request is attempted again
               |// from `/` assuming that it's an SPA route needing to be handled by the root bundle.
               |serve();
               |""".stripMargin

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
