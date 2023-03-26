package scalajsesbuild

import java.nio.file.Path

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
import org.typelevel.jawn.ast.JObject
import org.typelevel.jawn.ast.JParser
import sbt._
import sbt.AutoPlugin
import sbt.Keys._
import sbt.nio.Keys.fileInputExcludeFilter
import sbt.nio.Keys.fileInputs
import sbt.nio.file.FileTreeView

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
    ) // TODO consider doing the writing of the script upon call of this task, then use FileChanges to track changes to the script
    val esbuildBundle: TaskKey[ChangeStatus] = taskKey(
      "Bundles module with esbuild"
    )

    // workaround for https://github.com/sbt/sbt/issues/7164
    val esbuildFastLinkJSWrapper =
      taskKey[Seq[Path]]("Wraps fastLinkJS task to provide fileOutputs")
    val esbuildFullLinkJSWrapper =
      taskKey[Seq[Path]]("Wraps fullLinkJS task to provide fileOutputs")
  }

  import autoImport._

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
    esbuildCopyResources / fileInputs += (esbuildResourcesDirectory.value.toGlob / **),
    esbuildCopyResources / fileInputExcludeFilter := (esbuildCopyResources / fileInputExcludeFilter).value || (esbuildResourcesDirectory.value.toGlob / "node_modules" / **),
    esbuildCopyResources := {
      val targetDir = (esbuildInstall / crossTarget).value

      val fileChanges = esbuildCopyResources.inputFileChanges

      processFileChanges(
        fileChanges,
        esbuildResourcesDirectory.value,
        targetDir
      )

      if (fileChanges.hasChanges) {
        ChangeStatus.Dirty
      } else ChangeStatus.Pristine
    },
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

        val targetDir = (stageTask / esbuildInstall / crossTarget).value

        // parsing of esbuild bundling metadata is only necessary because of output hashing
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
    }.value,
    esbuildFastLinkJSWrapper := {
      fastLinkJS.value
      FileTreeView.default
        .list((fastLinkJS / scalaJSLinkerOutputDirectory).value.toGlob / **)
        .map { case (path, _) =>
          path
        }
    },
    esbuildFullLinkJSWrapper := {
      fullLinkJS.value
      FileTreeView.default
        .list((fullLinkJS / scalaJSLinkerOutputDirectory).value.toGlob / **)
        .map { case (path, _) =>
          path
        }
    }
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val (stageTask, stageTaskWrapper) =
      (stage.stageTask, stage.stageTaskWrapper)

    Seq(
      stageTask / esbuildCompile := {
        val changeStatus = esbuildInstall.value

        stageTaskWrapper.value

        val targetDir = (esbuildInstall / crossTarget).value

        val fileChanges = stageTaskWrapper.outputFileChanges

        processFileChanges(
          fileChanges,
          (stageTask / scalaJSLinkerOutputDirectory).value,
          targetDir
        )

        (if (fileChanges.hasChanges) {
           ChangeStatus.Dirty
         } else ChangeStatus.Pristine).combine(changeStatus)
      },
      stageTask / esbuildBundle / crossTarget := (esbuildInstall / crossTarget).value / "out",
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value

        val entryPoints = jsFileNames(stageTask.value.data)
          .map(jsFileName => s"'${(targetDir / jsFileName).absolutePath}'")
          .toSeq
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value.absolutePath

        // bundling is not necessary in `test` and `run` tasks, but it can be necessary when bundling for production
        // keep different use cases in mind and look into ways to accommodate them
        val hashOutputFiles = true

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
      },
      stageTask / esbuildBundle := {
        val log = streams.value.log

        val changeStatus = (stageTask / esbuildCompile).value
        // TODO add script change detection
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
