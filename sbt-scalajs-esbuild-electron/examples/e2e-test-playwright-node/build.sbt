import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scalajsesbuild.EsbuildElectronProcessConfiguration

lazy val root = (project in file("."))
  .aggregate(
    app,
    `e2e-test-playwright-node`
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

lazy val `e2e-test-playwright-node` =
  (project in file("e2e-test-playwright-node"))
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
