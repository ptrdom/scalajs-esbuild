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
