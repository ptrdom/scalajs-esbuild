import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scalajsesbuild.EsbuildElectronProcessConfiguration

lazy val root = (project in file("."))
  .aggregate(
    app,
    `e2e-test`
  )

ThisBuild / scalaVersion := "2.13.13"

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
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.2.0"
  )

lazy val `e2e-test` =
  (project in file("e2e-test"))
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
