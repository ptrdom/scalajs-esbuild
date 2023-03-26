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

  private[scalajsesbuild] sealed trait Stage {
    def stageTask: TaskKey[Attributed[Report]]
    def stageTaskWrapper: TaskKey[Seq[Path]]
  }
  private[scalajsesbuild] object Stage {
    case object FastOpt extends Stage {
      override def stageTask = fastLinkJS
      override def stageTaskWrapper: TaskKey[Seq[Path]] =
        esbuildFastLinkJSWrapper
    }
    case object FullOpt extends Stage {
      override def stageTask = fullLinkJS
      override def stageTaskWrapper: TaskKey[Seq[Path]] =
        esbuildFullLinkJSWrapper
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

  // TODO consider using sbt FileChanges instead
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
}
