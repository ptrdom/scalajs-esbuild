package scalajsesbuild

import java.nio.file.Files

import org.scalajs.jsenv.Input.Script
import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerOutputDirectory
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import sbt._
import sbt.AutoPlugin
import sbt.Keys._

import scala.jdk.CollectionConverters._

object ScalaJSEsbuildPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaJSPlugin

  object autoImport {
    val esbuildRunner: SettingKey[EsbuildRunner] =
      settingKey("Runs Esbuild commands")
    val esbuildPackageManager: SettingKey[PackageManager] =
      settingKey("Package manager to use for esbuild tasks")
    val esbuildResourcesDirectory: SettingKey[sbt.File] =
      settingKey("esbuild resource directory")
    val esbuildCopyResources: TaskKey[ChangeStatus] =
      taskKey("Copies over esbuild resources to target directory")
    val esbuildInstall: TaskKey[ChangeStatus] =
      taskKey(
        "Runs package manager's `install` in target directory on copied over esbuild resources"
      )
    val esbuildCompile: TaskKey[ChangeStatus] =
      taskKey(
        "Compiles module and copies output to target directory"
      )
    val esbuildBundleScript: TaskKey[String] = taskKey(
      "esbuild script used for bundling"
    )
    val esbuildBundle: TaskKey[ChangeStatus] = taskKey(
      "Bundles module with esbuild"
    )
  }

  import autoImport._

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

  sealed trait Stage
  object Stage {
    case object FullOpt extends Stage
    case object FastOpt extends Stage
  }

  // TODO rework with https://www.scala-sbt.org/1.x/docs/Howto-Track-File-Inputs-and-Outputs.html
  private def copyChanges(
      logger: Logger
  )(
      sourceDirectory: File,
      targetDirectory: File,
      currentDirectory: File
  ): ChangeStatus = {
    logger.debug(s"Walking directory [${currentDirectory.getAbsolutePath}]")
    Files
      .walk(currentDirectory.toPath)
      .iterator()
      .asScala
      .map(_.toFile)
      .filter(file => file.getAbsolutePath != currentDirectory.getAbsolutePath)
      .foldLeft[ChangeStatus](ChangeStatus.Pristine) {
        case (changeStatus, file) =>
          if (file.isDirectory) {
            copyChanges(logger)(sourceDirectory, targetDirectory, file)
          } else {
            val targetFile = new File(
              file.getAbsolutePath.replace(
                sourceDirectory.getAbsolutePath,
                targetDirectory.getAbsolutePath
              )
            )
            if (!Hash(file).sameElements(Hash(targetFile))) {
              logger.debug(
                s"File changed [${file.getAbsolutePath}], copying to [${targetFile.getAbsolutePath}]"
              )
              IO.copyFile(
                file,
                targetFile
              )
              ChangeStatus.Dirty
            } else {
              logger.debug(s"File not changed [${file.getAbsolutePath}]")
              changeStatus
            }
          }
      }
  }

  private def jsFileNames(report: Report) = {
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

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
    },
    esbuildRunner := EsbuildRunner.Default,
    esbuildResourcesDirectory := baseDirectory.value / "esbuild",
    esbuildPackageManager := PackageManager.Npm
  ) ++
    inConfig(Compile)(perConfigSettings) ++
    inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[_]] = Seq(
    unmanagedSourceDirectories += esbuildResourcesDirectory.value,
    esbuildInstall / crossTarget := {
      crossTarget.value /
        "esbuild" /
        (if (configuration.value == Compile) "main" else "test")
    },
    esbuildCopyResources := {
      val s = streams.value

      val targetDir = (esbuildInstall / crossTarget).value

      copyChanges(s.log)(
        esbuildResourcesDirectory.value,
        targetDir,
        esbuildResourcesDirectory.value
      )
    },
    watchSources := (watchSources.value ++ Seq(
      Watched.WatchSource(esbuildResourcesDirectory.value)
    )),
    esbuildInstall := {
      val changeStatus = esbuildCopyResources.value

      val s = streams.value

      val targetDir = (esbuildInstall / crossTarget).value

      val lockFile = esbuildPackageManager.value.lockFile

      FileFunction.cached(
        streams.value.cacheDirectory /
          "esbuild" /
          (if (configuration.value == Compile) "main" else "test"),
        inStyle = FilesInfo.hash
      ) { _ =>
        s.log.debug(s"Installing packages")

        esbuildPackageManager.value.install(s.log)(targetDir)

        IO.copyFile(
          targetDir / lockFile,
          esbuildResourcesDirectory.value / lockFile
        )

        Set.empty
      }(
        Set(
          esbuildResourcesDirectory.value / "package.json",
          esbuildResourcesDirectory.value / lockFile
        )
      )

      changeStatus
    },
    jsEnvInput := Def.taskDyn {
      val stageTask = scalaJSStage.value match {
        case org.scalajs.sbtplugin.Stage.FastOpt => fastLinkJS
        case org.scalajs.sbtplugin.Stage.FullOpt => fullLinkJS
      }
      Def.task {
        (stageTask / esbuildBundle).value

        jsFileNames(stageTask.value.data)
          .map((stageTask / esbuildBundle / crossTarget).value / _)
          .map(_.toPath)
          .map(Script)
          .toSeq
      }
    }.value
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val stageTask = stage match {
      case Stage.FastOpt => fastLinkJS
      case Stage.FullOpt => fullLinkJS
    }

    Seq(
      stageTask / esbuildCompile := {
        val changeStatus = esbuildInstall.value

        val targetDir = (esbuildInstall / crossTarget).value

        stageTask.value

        copyChanges(streams.value.log)(
          (stageTask / scalaJSLinkerOutputDirectory).value,
          targetDir,
          (stageTask / scalaJSLinkerOutputDirectory).value
        ).combine(changeStatus)
      },
      stageTask / esbuildBundle / crossTarget := (esbuildInstall / crossTarget).value / "out",
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value

        val stageTaskResult = stageTask.value

        val entryPoints = jsFileNames(stageTaskResult.data)
          .map(jsFileName =>
            s"'${(targetDir / jsFileName).absolutePath.replace("\\", "\\\\")}'"
          )
          .mkString(",")
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value.absolutePath
            .replace("\\", "\\\\")
        s"""
             |const esbuild = require("esbuild");
             |
             |const bundle = async () => {
             |  await esbuild.build({
             |    entryPoints: [$entryPoints],
             |    bundle: true,
             |    outdir: '$outdir',
             |  })
             |}
             |
             |bundle()
             |""".stripMargin
      },
      stageTask / esbuildBundle := {
        val log = streams.value.log

        val changeStatus = (stageTask / esbuildCompile).value
        val bundlingScript = (stageTask / esbuildBundleScript).value

        if (changeStatus == ChangeStatus.Dirty) {
          val targetDir = (esbuildInstall / crossTarget).value

          val scriptFileName = "sbt-scalajs-esbuild-bundle-script.cjs"
          IO.write(targetDir / scriptFileName, bundlingScript)

          esbuildRunner.value.run(log)(scriptFileName, targetDir)
        }

        changeStatus
      }
    )
  }
}
