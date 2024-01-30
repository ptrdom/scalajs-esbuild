import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scalajsesbuild.EsbuildElectronProcessConfiguration
import scala.sys.process._

lazy val root = (project in file("."))
  .aggregate(app, `integration-test`, `integration-test-selenium-jvm`)

ThisBuild / scalaVersion := "2.13.8"

val viteElectronBuildPackage =
  taskKey[Unit]("Generate package directory with electron-builder")
val viteElectronBuildDistributable =
  taskKey[Unit]("Package in distributable format with electron-builder")

lazy val app = (project in file("app"))
  .enablePlugins(ScalaJSEsbuildElectronPlugin)
  .settings(
    Test / test := {},
    // Suppress meaningless 'multiple main classes detected' warning
    Compile / mainClass := None,
    scalaJSModuleInitializers := Seq(
      ModuleInitializer
        .mainMethodWithArgs("example.Main", "main")
        .withModuleID("main"),
      ModuleInitializer
        .mainMethodWithArgs("example.Preload", "main")
        .withModuleID("preload"),
      ModuleInitializer
        .mainMethodWithArgs("example.Renderer", "main")
        .withModuleID("renderer")
    ),
    Compile / esbuildElectronProcessConfiguration := new EsbuildElectronProcessConfiguration(
      "main",
      Set("preload"),
      Set("renderer")
    ),
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.2.0",
    Seq(Compile, Test)
      .flatMap { config =>
        inConfig(config)(
          Seq(Stage.FastOpt, Stage.FullOpt).flatMap { stage =>
            val stageTask = stage match {
              case Stage.FastOpt => fastLinkJS
              case Stage.FullOpt => fullLinkJS
            }

            def fn(args: List[String] = Nil) = Def.task {
              val log = streams.value.log

              (stageTask / esbuildBundle).value

              val targetDir = (esbuildInstall / crossTarget).value

              val exitValue = Process(
                "node" :: "node_modules/electron-builder/cli" :: Nil ::: args,
                targetDir
              ).run(log).exitValue()
              if (exitValue != 0) {
                sys.error(s"Nonzero exit value: $exitValue")
              } else ()
            }

            Seq(
              stageTask / viteElectronBuildPackage := fn("--dir" :: Nil).value,
              stageTask / viteElectronBuildDistributable := fn().value
            )
          }
        )
      }
  )

lazy val `integration-test-selenium-jvm` =
  (project in file("integration-test-selenium-jvm"))
    .settings(
      Test / test := (Test / test).dependsOn {
        Def.taskDyn {
          val stageTask = (app / Compile / scalaJSStage).value match {
            case Stage.FastOpt => fastLinkJS
            case Stage.FullOpt => fullLinkJS
          }
          Def.task {
            (((app / Compile) / stageTask) / esbuildBundle).value
          }
        }
      }.value,
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test",
      libraryDependencies ++= Seq(
        "org.scalatestplus" %% "selenium-4-7" % "3.2.15.0" % "test",
        "org.seleniumhq.selenium" % "selenium-java" % "4.16.1" % "test"
      ) // should be upgraded when Electron upgrades its chromium version
    )

lazy val `integration-test` = (project in file("integration-test"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.CommonJSModule)
    },
    Test / jsEnv := Def.taskDyn {
      val stageTask = (app / Compile / scalaJSStage).value match {
        case Stage.FastOpt => fastLinkJS
        case Stage.FullOpt => fullLinkJS
      }

      Def.task {
        (((app / Compile) / stageTask) / esbuildBundle).value

        val sourcesDirectory =
          (((app / Compile) / esbuildInstall) / crossTarget).value
        val outputDirectory =
          ((((app / Compile) / stageTask) / esbuildBundle) / crossTarget).value
        val mainModuleID =
          ((app / Compile) / esbuildElectronProcessConfiguration).value.mainModuleID

        val nodePath = (sourcesDirectory / "node_modules").absolutePath
        val mainPath = (outputDirectory / s"$mainModuleID.js").absolutePath

        new NodeJSEnv(
          NodeJSEnv
            .Config()
            .withEnv(
              Map(
                "NODE_PATH" -> nodePath,
                "MAIN_PATH" -> mainPath
              )
            )
        )
      }
    }.value,
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
  )
  .dependsOn(app)
