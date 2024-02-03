import java.nio.file.Path

import org.scalajs.ir.Names.DefaultModuleID
import org.scalajs.jsenv.Input
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import sbt.*
import sbt.Keys.configuration
import sbt.Keys.crossTarget
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildBundle
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

  private[scalajsesbuild] def extractEntryPointsByPlatform(
      report: Report,
      moduleConfigurations: Map[String, EsbuildScalaJSModuleConfiguration]
  ) = {
    report match {
      case report: unstable.ReportImpl =>
        report.publicModules
          .foldLeft(
            Map.empty[EsbuildScalaJSModuleConfiguration.EsbuildPlatform, Set[
              String
            ]]
          ) { case (acc, publicModule) =>
            val platform = moduleConfigurations
              .getOrElse(
                publicModule.moduleID,
                sys.error(
                  s"esbuild module configuration missing for moduleID [${publicModule.moduleID}]"
                )
              )
              .platform
            acc.updated(
              platform,
              acc.getOrElse(platform, Set.empty) + publicModule.jsFileName
            )
          }
      case unhandled =>
        sys.error(s"Unhandled report type [$unhandled]")
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

  private[scalajsesbuild] def resolveMainModule(
      report: Report
  ) = {
    report.publicModules
      .find(_.moduleID == DefaultModuleID)
      .getOrElse(
        sys.error(
          "Cannot determine `jsEnvInput`: Linking result does not have a " +
            s"module named `$DefaultModuleID`. Set jsEnvInput manually?\n" +
            s"Full report:\n$report"
        )
      )
  }

  private[scalajsesbuild] def jsEnvInputTask = Def.taskDyn {
    val stageTask = scalaJSStage.value.stageTask
    Def.task {
      (stageTask / esbuildBundle).value

      val report = stageTask.value.data
      val mainModule = resolveMainModule(report)

      val path =
        ((stageTask / esbuildBundle / crossTarget).value / mainModule.jsFileName).toPath
      Seq(Input.Script(path))
    }
  }
}
