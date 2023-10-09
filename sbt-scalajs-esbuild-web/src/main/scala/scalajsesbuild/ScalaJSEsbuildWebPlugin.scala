package scalajsesbuild

import org.scalajs.jsenv.Input.Script
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import org.typelevel.jawn.ast.JObject
import org.typelevel.jawn.ast.JParser
import sbt.AutoPlugin
import sbt._
import sbt.Keys._
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundleScript
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildCompile
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildInstall
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildRunner

import java.nio.file.Path
import scala.sys.process._

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

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] =
    Seq(
      esbuildBundleHtmlEntryPoints := Seq(
        Path.of("index.html")
      )
    ) ++ inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[_]] = Seq(
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

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val stageTask = stage.stageTask

    Seq(
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value
        val stageTaskReport = stageTask.value.data
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value
        val htmlEntryPoints = esbuildBundleHtmlEntryPoints.value
        require(
          !htmlEntryPoints.forall(_.isAbsolute),
          "HTML entry point paths must be relative"
        )
        generateEsbuildBundleScript(
          targetDir = targetDir,
          outdir = outdir,
          stageTaskReport = stageTaskReport,
          outputFilesDirectory = Some("assets"),
          hashOutputFiles = true,
          htmlEntryPoints = htmlEntryPoints
        )
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
        stageTask / esbuildServeScript := {
          val targetDir = (esbuildInstall / crossTarget).value

          val entryPoints = jsFileNames(stageTask.value.data)
            .map(targetDir / _)
            .toSeq
          val outdir =
            (stageTask / esbuildServeStart / crossTarget).value

          val htmlEntryPoints = esbuildBundleHtmlEntryPoints.value

          // language=JS
          s"""
             |const http = require('http');
             |const esbuild = require('esbuild');
             |const jsdom = require("jsdom")
             |const { JSDOM } = jsdom;
             |const fs = require('fs');
             |const path = require('path');
             |
             |$htmlTransformScript
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
             |        build.onEnd(result => {
             |          const metafileName = 'sbt-scalajs-esbuild-serve-meta.json';
             |          if (!result.metafile) {
             |            console.warn("Metafile missing in build result")
             |            fs.writeFileSync(metafileName, '{}');
             |          } else {
             |            fs.writeFileSync(metafileName, JSON.stringify(result.metafile));
             |          }
             |        });
             |      },
             |    }];
             |
             |    const ctx  = await esbuild.context({
             |${esbuildOptions(
              entryPoints = entryPoints,
              outdir = outdir,
              outputFilesDirectory = Some("assets"),
              hashOutputFiles = false,
              minify = false,
              spaces = 6
            )}
             |      plugins: plugins,
             |    });
             |
             |    await ctx.watch()
             |
             |    const { host, port } = await ctx.serve({
             |        servedir: '${outdir.toPath.toStringEscaped}',
             |        port: 8001
             |    });
             |
             |    // Create a second (proxy) server that will forward requests to esbuild.
             |    const proxy = http.createServer((req, res) => {
             |        const metaPath = path.join(__dirname, 'sbt-scalajs-esbuild-serve-meta.json');
             |        let meta;
             |        try {
             |          meta = JSON.parse(fs.readFileSync(metaPath));
             |        } catch (error) {
             |          res.writeHead(500);
             |          res.end('META file ['+metaPath+'] not found');
             |        }
             |
             |        if (meta) {
             |          // forwardRequest forwards an http request through to esbuid.
             |          const forwardRequest = (path) => {
             |              const options = {
             |                  hostname: host,
             |                  port,
             |                  path,
             |                  method: req.method,
             |                  headers: req.headers,
             |              };
             |
             |          const multipleEntryPointsFound = ${htmlEntryPoints.size > 1};
             |
             |          if (multipleEntryPointsFound && path === "/") {
             |            res.writeHead(500);
             |            res.end('Multiple html entry points defined, unable to pick single root');
             |          } else {
             |            if (path === "/" || path.endsWith(".html")) {
             |              let file;
             |              if (path === "/") {
             |                file = '/${htmlEntryPoints.head}';
             |              } else {
             |                file = path;
             |              }
             |
             |              const htmlFilePath = "."+file;
             |
             |              if (fs.existsSync(htmlFilePath)) {
             |                try {
             |                  res.writeHead(200, {"Content-Type": "text/html"});
             |                  res.end(htmlTransform(esbuildLiveReload(fs.readFileSync(htmlFilePath)), '${outdir.toPath.toStringEscaped}', meta));
             |                } catch (error) {
             |                  res.writeHead(500);
             |                  res.end('Failed to transform html ['+error+']');
             |                }
             |              } else {
             |                res.writeHead(404);
             |                res.end('HTML file ['+htmlFilePath+'] not found');
             |              }
             |            } else {
             |              const proxyReq = http.request(options, (proxyRes) => {
             |                if (proxyRes.statusCode === 404 && !multipleEntryPointsFound) {
             |                  // If esbuild 404s the request, assume it's a route needing to
             |                  // be handled by the JS bundle, so forward a second attempt to `/`.
             |                  return forwardRequest("/");
             |                }
             |
             |                // Otherwise esbuild handled it like a champ, so proxy the response back.
             |                res.writeHead(proxyRes.statusCode, proxyRes.headers);
             |                proxyRes.pipe(res, { end: true });
             |              });
             |
             |              req.pipe(proxyReq, { end: true });
             |            }
             |          }
             |        };
             |        // When we're called pass the request right through to esbuild.
             |        forwardRequest(req.url);
             |      }
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
