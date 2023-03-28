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

  private[scalajsesbuild] def escapePathString(pathString: String) =
    pathString.replace("\\", "\\\\")

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
      stageTask: TaskKey[Attributed[Report]],
      hashOutputFiles: Boolean
  ) = Def.task {
    val targetDir = (esbuildInstall / crossTarget).value

    val entryPoints = jsFileNames(stageTask.value.data)
      .map(jsFileName => s"'${(targetDir / jsFileName).absolutePath}'")
      .toSeq
    val outdir =
      (stageTask / esbuildBundle / crossTarget).value.absolutePath

    // language=JS
    s"""
       |const esbuild = require('esbuild');
       |const fs = require('fs');
       |
       |const bundle = async () => {
       |  const result = await esbuild.build({
       |    ${esbuildOptions(
        entryPoints,
        outdir,
        hashOutputFiles = hashOutputFiles,
        minify = true
      )}
       |  });
       |
       |  fs.writeFileSync('sbt-scalajs-esbuild-bundle-meta.json', JSON.stringify(result.metafile));
       |}
       |
       |bundle();
       |""".stripMargin
  }
}
