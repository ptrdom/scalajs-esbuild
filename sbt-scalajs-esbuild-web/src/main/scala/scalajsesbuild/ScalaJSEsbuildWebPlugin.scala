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
    inConfig(Compile)(perConfigSettings) ++
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
      stageTask / esbuildBundleHtmlEntryPoints := Seq(
        Path.of("index.html")
      ),
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value
        val stageTaskReport = stageTask.value.data
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value
        val htmlEntryPoints = (stageTask / esbuildBundleHtmlEntryPoints).value
        require(
          !htmlEntryPoints.forall(_.isAbsolute),
          "HTML entry point paths must be relative"
        )
        generateEsbuildBundleScript(
          targetDir,
          outdir,
          stageTaskReport,
          hashOutputFiles = true,
          htmlEntryPoints
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
             |        let count = 0;
             |        build.onEnd(result => {
             |          if (count++ === 0) {
             |            fs.writeFileSync('sbt-scalajs-esbuild-serve-meta.json', JSON.stringify(result.metafile));
             |          } else {
             |            fs.writeFileSync('sbt-scalajs-esbuild-serve-meta.json', JSON.stringify(result.metafile));
             |          }
             |        });
             |      },
             |    }];
             |
             |    const ctx  = await esbuild.context({
             |${esbuildOptions(
              entryPoints,
              outdir,
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
             |          fs.readFile("."+file, function (err, data) {
             |            if (err) {
             |              throw err;
             |            } else {
             |              res.writeHead(200, {"Content-Type": "text/html"});
             |
             |              const meta = JSON.parse(fs.readFileSync(path.join(__dirname, 'sbt-scalajs-esbuild-serve-meta.json')));
             |              res.write(htmlTransform(esbuildLiveReload(data), '${outdir.toPath.toStringEscaped}', meta));
             |
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
