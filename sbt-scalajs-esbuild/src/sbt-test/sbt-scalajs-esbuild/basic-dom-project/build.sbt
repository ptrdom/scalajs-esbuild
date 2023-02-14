import org.scalajs.sbtplugin.Stage
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import scala.sys.process._

enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "2.13.8"

scalaJSUseMainModuleInitializer := true

libraryDependencies ++= Seq(
  "org.scala-js" %%% "scalajs-dom" % "2.2.0",
  "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
)

lazy val perConfigSettings = Seq(
  jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
    org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
      .Config()
      .withEnv(
        Map(
          "NODE_PATH" -> ((esbuildInstall / crossTarget).value / "node_modules").absolutePath
        )
      )
  )
) ++ perScalaJSStageSettings(Stage.FastOpt) ++ perScalaJSStageSettings(
  Stage.FullOpt
)

inConfig(Compile)(perConfigSettings)
inConfig(Test)(perConfigSettings)

lazy val esbuildServeStart = taskKey[Unit]("")
lazy val esbuildServeStop = taskKey[Unit]("")

def jsFileNames(report: Report) = {
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

def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
  val stageTask = stage match {
    case Stage.FastOpt => fastLinkJS
    case Stage.FullOpt => fullLinkJS
  }

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

//TODO setup production bundling with hashes, consider altering esbuildBundle for it
//TODO setup injection of scripts for both serve and production bundling
