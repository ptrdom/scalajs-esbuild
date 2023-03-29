import java.nio.file.Path
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import sbt._
import sbt.Keys.crossTarget
import sbt.sbtOptionSyntaxOptionIdOps
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildFastLinkJSWrapper
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildFullLinkJSWrapper
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildInstall

package object scalajsesbuild {

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
         |logLevel: "info",""".stripMargin.some,
      if (hashOutputFiles) {
        """entryNames: '[name]-[hash]',
          |assetNames: '[name]-[hash]',""".stripMargin.some
      } else None,
      if (minify) {
        "minify: true".some
      } else None
    ).flatten.mkString.linesIterator
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
        entryPoints,
        outdir,
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
      |  const workingDirectory = __dirname;
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
      |}""".stripMargin
  }
}
