package scalajs

import java.nio.file.Path

import org.scalajs.jsenv.Input
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import sbt.*
import sbt.Keys.crossTarget
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildFastLinkJSWrapper
import scalajs.esbuild.ScalaJSEsbuildPlugin.autoImport.esbuildFullLinkJSWrapper

package object esbuild {

  private[esbuild] val defaultModuleID = "main"

  private[esbuild] val isWindows =
    sys.props("os.name").toLowerCase.contains("win")

  private[esbuild] implicit class ScalaJSStageOps(
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

  private[esbuild] def jsFileNames(report: Report) = {
    report match {
      case report: unstable.ReportImpl =>
        report.publicModules.map(publicModule => publicModule.jsFileName).toSet
      case unhandled =>
        sys.error(s"Unhandled report type [$unhandled]")
    }
  }

  private[esbuild] def processFileChanges(
      fileChanges: FileChanges,
      sourceDirectory: File,
      targetDirectory: File,
      logger: Logger
  ): Unit = {
    val sourceDirPath = sourceDirectory.toPath.toAbsolutePath.normalize
    val targetDirPath = targetDirectory.toPath.toAbsolutePath.normalize

    def isUnderSource(path: Path): Boolean =
      path.toAbsolutePath.normalize.startsWith(sourceDirPath)

    def toTargetFile(sourcePath: Path): File =
      targetDirPath
        .resolve(sourceDirPath.relativize(sourcePath.toAbsolutePath.normalize))
        .toFile

    // Only `deleted` can contain entries outside sourceDirectory: created,
    // modified and unmodified come from globbing current files and so always
    // live under the current sourceDirectory. A `deleted` path that does not
    // is a sign sbt's fileInputs cache references a previous sourceDirectory
    // value (e.g. esbuildResourcesDirectory was reassigned, or `clean` only
    // partially cleared the change-tracking cache).
    val staleDeleted = fileChanges.deleted.filterNot(isUnderSource)

    if (staleDeleted.nonEmpty) {
      // Translating those paths through targetDirectory would either produce
      // a `..`-escaping path or - with the previous String.replace
      // implementation - silently delete files in the user's source tree.
      // Recover by copying every file currently visible under sourceDirectory,
      // equivalent to a post-`clean` first run; sbt commits the file stamps
      // at end-of-task, so the cache is back in sync for subsequent runs.
      logger.warn(
        s"Detected ${staleDeleted.size} stale fileInputs deletion entr" +
          (if (staleDeleted.size == 1) "y" else "ies") +
          s" outside sourceDirectory [$sourceDirPath]; " +
          "falling back to a full copy of sourceDirectory into " +
          s"[$targetDirPath]."
      )
      (fileChanges.created ++ fileChanges.modified ++ fileChanges.unmodified)
        .foreach { path =>
          IO.copyFile(path.toFile, toTargetFile(path))
        }
    } else {
      (fileChanges.created ++ fileChanges.modified)
        .foreach { path =>
          IO.copyFile(path.toFile, toTargetFile(path))
        }
      fileChanges.deleted.foreach { path =>
        IO.delete(toTargetFile(path))
      }
    }
  }

  implicit private[esbuild] class FileChangesOps(
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

  private[esbuild] def resolveMainModule(
      report: Report
  ) = {
    report.publicModules
      .find(_.moduleID == defaultModuleID)
      .getOrElse(
        sys.error(
          "Cannot determine `jsEnvInput`: Linking result does not have a " +
            s"module named `$defaultModuleID`. Set jsEnvInput manually?\n" +
            s"Full report:\n$report"
        )
      )
  }

  private[esbuild] def jsEnvInputTask = Def.taskDyn {
    val stageTask = scalaJSStage.value.stageTask
    Def.task {
      (stageTask / esbuildBundle).value

      val report = stageTask.value.data
      val mainModule = resolveMainModule(report)

      val path =
        ((esbuildBundle / crossTarget).value / mainModule.jsFileName).toPath
      Seq(Input.Script(path))
    }
  }

  private[esbuild] sealed trait EsbuildPlatform {
    def jsValue: String = s"'${toString.toLowerCase}'"
  }
  private[esbuild] object EsbuildPlatform {
    case object Browser extends EsbuildPlatform
    case object Node extends EsbuildPlatform

    def apply(moduleKind: ModuleKind): EsbuildPlatform = {
      moduleKind match {
        case ModuleKind.CommonJSModule => Node
        case _                         => Browser
      }
    }
  }
}
