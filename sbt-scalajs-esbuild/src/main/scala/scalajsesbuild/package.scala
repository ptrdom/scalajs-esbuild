import java.nio.file.Path
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import sbt.*
import sbt.sbtOptionSyntaxOptionIdOps
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildFastLinkJSWrapper
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildFullLinkJSWrapper

package object scalajsesbuild {

  private[scalajsesbuild] val isWindows =
    sys.props("os.name").toLowerCase.contains("win")

  private[scalajsesbuild] implicit class ScalaJSStageOps(
      stage: org.scalajs.sbtplugin.Stage
  ) {
    def stageTask: TaskKey[sbt.Attributed[Report]] = stage match {
      case org.scalajs.sbtplugin.Stage.FastOpt => fastLinkJS
      case org.scalajs.sbtplugin.Stage.FullOpt => fullLinkJS
    }

    def stageTaskWrapper: TaskKey[Seq[Path]] = stage match {
      case org.scalajs.sbtplugin.Stage.FastOpt => esbuildFastLinkJSWrapper
      case org.scalajs.sbtplugin.Stage.FullOpt => esbuildFullLinkJSWrapper
    }
  }

  private[scalajsesbuild] def jsFileNames(report: Report) = {
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

  private[scalajsesbuild] def esbuildLoaders = {
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

  private[scalajsesbuild] def esbuildOptions(
      entryPoints: Seq[File],
      outdir: File,
      outputFilesDirectory: Option[String],
      hashOutputFiles: Boolean,
      minify: Boolean,
      spaces: Int
  ) = {
    Seq(
      s"""|entryPoints: ${entryPoints
           .map(_.toPath.toStringEscaped)
           .toJsArrayString},
         |bundle: true,
         |outdir: '${outdir.toPath.toStringEscaped}',
         |loader: { $esbuildLoaders },
         |metafile: true,
         |logOverride: {
         |  'equals-negative-zero': 'silent',
         |},
         |logLevel: "info",
         |entryNames: '${outputFilesDirectory
           .map(_ + "/")
           .getOrElse("")}[name]${if (hashOutputFiles) "-[hash]" else ""}',
         |assetNames: '${outputFilesDirectory
           .map(_ + "/")
           .getOrElse("")}[name]${if (hashOutputFiles) "-[hash]"
         else ""}',""".stripMargin.some,
      if (minify) {
        "minify: true,".some
      } else None,
      if (outputFilesDirectory.nonEmpty) {
        """publicPath: "/",""".some
      } else None
    ).flatten
      .mkString("\n")
      .linesIterator
      .map((" " * spaces) + _)
      .mkString("\n")
  }

  implicit private[scalajsesbuild] class SeqOps[T](seq: Seq[T]) {
    def toJsArrayString: String = {
      seq
        .map("'" + _ + "'")
        .mkString("[", ", ", "]")
    }
  }

  implicit private[scalajsesbuild] class PathOps(path: Path) {
    def toStringEscaped = {
      path.toString.replace("\\", "\\\\")
    }
  }

  private[scalajsesbuild] def processFileChanges(
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

  implicit private[scalajsesbuild] class FileChangesOps(
      fileChanges: FileChanges
  ) {
    def ++(that: FileChanges) = {
      FileChanges(
        created = fileChanges.created ++ that.created,
        deleted = fileChanges.deleted ++ that.deleted,
        modified = fileChanges.modified ++ that.modified,
        unmodified = fileChanges.unmodified ++ that.unmodified
      )
    }
  }

  private[scalajsesbuild] def generateEsbuildBundleScript(
      targetDir: File,
      outdir: File,
      stageTaskReport: Report,
      outputFilesDirectory: Option[String],
      hashOutputFiles: Boolean,
      htmlEntryPoints: Seq[Path]
  ) = {
    val entryPoints = jsFileNames(stageTaskReport)
      .map(targetDir / _)
      .toSeq

    // language=JS
    s"""
       |const esbuild = require('esbuild');
       |const fs = require('fs');
       |
       |${if (htmlEntryPoints.nonEmpty)
        Seq(
          """const jsdom = require('jsdom')
            |const { JSDOM } = jsdom;
            |const path = require('path');""".stripMargin,
          "",
          htmlTransformScript
        ).mkString("\n")
      else ""}
       |
       |const bundle = async () => {
       |  const result = await esbuild.build({
       |${esbuildOptions(
        entryPoints = entryPoints,
        outdir = outdir,
        outputFilesDirectory = outputFilesDirectory,
        hashOutputFiles = hashOutputFiles,
        minify = true,
        spaces = 4
      )}
       |  });
       |
       |  fs.writeFileSync('sbt-scalajs-esbuild-bundle-meta.json', JSON.stringify(result.metafile));
       |
       |  const htmlEntryPoints = ${htmlEntryPoints
        .map(htmlEntryPoint =>
          Path
            .of(targetDir.absolutePath, htmlEntryPoint.toString)
            .toStringEscaped
        )
        .toJsArrayString};
       |
       |  htmlEntryPoints
       |    .forEach((htmlEntryPoint) => {
       |      const html = fs.readFileSync(htmlEntryPoint);
       |      const transformedHtml = htmlTransform(html, '${outdir.toPath.toStringEscaped}', result.metafile);
       |      const relativePath = path.relative(__dirname, htmlEntryPoint);
       |      fs.writeFileSync(path.join('${outdir.toPath.toStringEscaped}', relativePath), transformedHtml);
       |    });
       |}
       |
       |bundle();
       |""".stripMargin
  }

  private[scalajsesbuild] val htmlTransformScript = {
    // language=JS
    s"""const htmlTransform = (htmlString, outDirectory, meta) => {
      |  if (!meta.outputs) {
      |    throw new Error('Meta file missing output metadata');
      |  }
      |
      |  const workingDirectory = __dirname;
      |
      |  const toHtmlPath = (filePath) => filePath.split(path.sep).join(path.posix.sep);
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
      |     el.src = toHtmlPath(el.src.replace(output.entryPoint, path.relative(outDirectory, path.join(workingDirectory, outputBundle))));
      |     if (output.cssBundle) {
      |       const link = dom.window.document.createElement("link");
      |       link.rel = "stylesheet";
      |       link.href = (absolute ? "/" : "") + toHtmlPath(path.relative(outDirectory, path.join(workingDirectory, output.cssBundle)));
      |       el.parentNode.insertBefore(link, el.nextSibling);
      |     }
      |    }
      |  });
      |  return dom.serialize();
      |}""".stripMargin
  }
}
