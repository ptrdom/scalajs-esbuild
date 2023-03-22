package scalajsesbuild

import java.nio.file.Path

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
import sbt.nio.Keys.fileInputExcludeFilter
import sbt.nio.Keys.fileInputs
import sbt.nio.file.FileTreeView

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
    val esbuildServeScript: TaskKey[String] = taskKey(
      "esbuild script used for serving"
    )
    val esbuildServeStart =
      taskKey[Unit]("Runs esbuild serve on target directory")
    val esbuildServeStop =
      taskKey[Unit]("Stops running esbuild serve on target directory")

    // workaround for https://github.com/sbt/sbt/issues/7164
    val esbuildFastLinkJSWrapper =
      taskKey[Seq[Path]]("Wraps fastLinkJS task to provide fileOutputs")
    val esbuildFullLinkJSWrapper =
      taskKey[Seq[Path]]("Wraps fullLinkJS task to provide fileOutputs")
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

  private def esbuildLoaders = {
    // taken from Vite's KNOWN_ASSET_TYPES constant
    Seq(
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
  }

  private def esbuildOptions(
      entryPoints: Seq[String],
      outdir: String,
      hashOutputFiles: Boolean,
      minify: Boolean
  ) = {
    val entryPointsFn: Seq[String] => String = _.map(escapePathString)
      .mkString(",")
    val outdirFn: String => String = escapePathString

    Seq(
      s"""
         |  entryPoints: [${entryPointsFn(entryPoints)}],
         |  bundle: true,
         |  outdir: '${outdirFn(outdir)}',
         |  loader: { $esbuildLoaders },
         |  metafile: true,
         |  logOverride: {
         |    'equals-negative-zero': 'silent',
         |  },
         |  logLevel: "info",
         |""".stripMargin.some,
      if (hashOutputFiles) {
        """
          |  entryNames: '[name]-[hash]',
          |  assetNames: '[name]-[hash]',
          |""".stripMargin.some
      } else None,
      if (minify) {
        """
          |  minify: true,
          |""".stripMargin.some
      } else None
    ).flatten.mkString
  }

  private def escapePathString(pathString: String) =
    pathString.replace("\\", "\\\\")

  private def processFileChanges(
      fileChanges: FileChanges,
      sourceDirectory: File,
      targetDirectory: File
  ): Unit = {
    def toTargetFile(
        sourcePath: Path,
        sourceDirectory: File,
        targetDirectory: File
    ): File = {
      new File(
        sourcePath.toFile.getAbsolutePath.replace(
          sourceDirectory.getAbsolutePath,
          targetDirectory.getAbsolutePath
        )
      )
    }

    (fileChanges.created ++ fileChanges.modified)
      .foreach { path =>
        IO.copyFile(
          path.toFile,
          toTargetFile(path, sourceDirectory, targetDirectory)
        )
      }

    fileChanges.deleted.foreach { path =>
      IO.delete(
        toTargetFile(path, sourceDirectory, targetDirectory)
      )
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
    esbuildCopyResources / fileInputs += (esbuildResourcesDirectory.value.toGlob / **),
    esbuildCopyResources / fileInputExcludeFilter := (esbuildCopyResources / fileInputExcludeFilter).value || (esbuildResourcesDirectory.value.toGlob / "node_modules" / **),
    esbuildCopyResources := {
      val targetDir = (esbuildInstall / crossTarget).value

      val fileChanges = esbuildCopyResources.inputFileChanges

      processFileChanges(
        fileChanges,
        esbuildResourcesDirectory.value,
        targetDir
      )

      if (fileChanges.hasChanges) {
        ChangeStatus.Dirty
      } else ChangeStatus.Pristine
    },
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
    }.value,
    esbuildFastLinkJSWrapper := {
      fastLinkJS.value
      FileTreeView.default
        .list((fastLinkJS / scalaJSLinkerOutputDirectory).value.toGlob / **)
        .map { case (path, _) =>
          path
        }
    },
    esbuildFullLinkJSWrapper := {
      fullLinkJS.value
      FileTreeView.default
        .list((fullLinkJS / scalaJSLinkerOutputDirectory).value.toGlob / **)
        .map { case (path, _) =>
          path
        }
    }
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val (stageTask, stageTaskWrapper) = stage match {
      case Stage.FastOpt => (fastLinkJS, esbuildFastLinkJSWrapper)
      case Stage.FullOpt => (fullLinkJS, esbuildFullLinkJSWrapper)
    }

    Seq(
      stageTask / esbuildCompile := {
        val changeStatus = esbuildInstall.value

        stageTaskWrapper.value

        val targetDir = (esbuildInstall / crossTarget).value

        val fileChanges = stageTaskWrapper.outputFileChanges

        processFileChanges(
          fileChanges,
          (stageTask / scalaJSLinkerOutputDirectory).value,
          targetDir
        )

        (if (fileChanges.hasChanges) {
           ChangeStatus.Dirty
         } else ChangeStatus.Pristine).combine(changeStatus)
      },
      stageTask / esbuildBundle / crossTarget := (esbuildInstall / crossTarget).value / "out",
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value

        val entryPoints = jsFileNames(stageTask.value.data)
          .map(jsFileName => s"'${(targetDir / jsFileName).absolutePath}'")
          .toSeq
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value.absolutePath

        s"""
             |const esbuild = require('esbuild');
             |const fs = require('fs');
             |
             |const bundle = async () => {
             |  const result = await esbuild.build({
             |    ${esbuildOptions(
            entryPoints,
            outdir,
            hashOutputFiles =
              true, // TODO either do not hash in `run` and `test` or interpret bundling result in `jsEnvInput`
            minify = true
          )}
             |  });
             |
             |  fs.writeFileSync('meta.json', JSON.stringify(result.metafile));
             |}
             |
             |bundle();
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
        stageTask / esbuildServeScript := {
          val targetDir = (esbuildInstall / crossTarget).value

          val entryPoints = jsFileNames(stageTask.value.data)
            .map(jsFileName => s"'${(targetDir / jsFileName).absolutePath}'")
            .toSeq
          val outdir =
            (stageTask / esbuildServeStart / crossTarget).value.absolutePath
          val outdirEscaped = escapePathString(outdir)

          s"""
             |const http = require('http');
             |const esbuild = require('esbuild');
             |const jsdom = require("jsdom")
             |const { JSDOM } = jsdom;
             |const fs = require('fs');
             |const path = require('path');
             |
             |const htmlTransform = (htmlString, outDirectory) => {
             |  const workingDirectory = __dirname;
             |
             |  const meta = JSON.parse(fs.readFileSync(path.join(__dirname, "meta.json")));
             |
             |  const dom = new JSDOM(htmlString);
             |  dom.window.document.querySelectorAll("script").forEach((el) => {
             |    let output;
             |    let outputBundle;
             |    Object.keys(meta.outputs).every((key) => {
             |      const maybeOutput = meta.outputs[key];
             |      if (el.src.endsWith(maybeOutput.entryPoint)) {
             |        output = maybeOutput;
             |        outputBundle = key;
             |        return false;
             |      }
             |      return true;
             |    })
             |    if (output) {
             |     let absolute = el.src.startsWith("/");
             |     el.src = el.src.replace(output.entryPoint, path.relative(outDirectory, path.join(workingDirectory, outputBundle)));
             |     if (output.cssBundle) {
             |       const link = dom.window.document.createElement("link");
             |       link.rel = "stylesheet";
             |       link.href = (absolute ? "/" : "") + path.relative(outDirectory, path.join(workingDirectory, output.cssBundle));
             |       el.parentNode.insertBefore(link, el.nextSibling);
             |     }
             |    }
             |  });
             |  return dom.serialize();
             |}
             |
             |const esbuildLiveReload = (htmlString) => {
             |  return htmlString
             |    .toString()
             |    .replace("</head>", `
             |      <script type="text/javascript">
             |        // Based on https://esbuild.github.io/api/#live-reload
             |        new EventSource('/esbuild').addEventListener('change', e => {
             |          const { added, removed, updated } = JSON.parse(e.data)
             |
             |          if (!added.length && !removed.length && updated.length === 1) {
             |            for (const link of document.getElementsByTagName("link")) {
             |              const url = new URL(link.href)
             |
             |              if (url.host === location.host && url.pathname === updated[0]) {
             |                const next = link.cloneNode()
             |                next.href = updated[0] + '?' + Math.random().toString(36).slice(2)
             |                next.onload = () => link.remove()
             |                link.parentNode.insertBefore(next, link.nextSibling)
             |                return
             |              }
             |            }
             |          }
             |
             |          location.reload()
             |        })
             |      </script>
             |    </head>
             |    `);
             |}
             |
             |const serve = async () => {
             |    // Start esbuild's local web server. Random port will be chosen by esbuild.
             |
             |    const plugins = [{
             |      name: 'metafile-plugin',
             |      setup(build) {
             |        let count = 0;
             |        build.onEnd(result => {
             |          if (count++ === 0) {
             |            fs.writeFileSync('meta.json', JSON.stringify(result.metafile));
             |          } else {
             |            fs.writeFileSync('meta.json', JSON.stringify(result.metafile));
             |          }
             |        });
             |      },
             |    }];
             |
             |    const ctx  = await esbuild.context({
             |      ${esbuildOptions(
              entryPoints,
              outdir,
              hashOutputFiles = false,
              minify = false
            )}
             |      plugins: plugins,
             |    });
             |
             |    await ctx.watch()
             |
             |    const { host, port } = await ctx.serve({
             |        servedir: '$outdirEscaped',
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
             |        if (req.url === "/" || req.url.endsWith(".html")) {
             |          let file;
             |          if (req.url === "/") {
             |            file = "/index.html";
             |          } else {
             |            file = req.url;
             |          }
             |
             |          fs.readFile("."+file, function (err, html) {
             |            if (err) {
             |              throw err;
             |            } else {
             |              res.writeHead(200, {"Content-Type": "text/html"});
             |              res.write(htmlTransform(esbuildLiveReload(html), "$outdirEscaped"));
             |              res.end();
             |            }
             |          });
             |        } else {
             |          // When we're called pass the request right through to esbuild.
             |          forwardRequest(req.url);
             |        }
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
        },
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
