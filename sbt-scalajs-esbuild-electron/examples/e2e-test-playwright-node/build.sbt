import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.sbtplugin.Stage
import scalajs.esbuild.electron.EsbuildElectronProcessConfiguration

lazy val root = (project in file("."))
  .aggregate(
    app,
    `e2e-test`
  )

ThisBuild / scalaVersion := "2.13.14"

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
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0"
  )

lazy val `e2e-test` =
  (project in file("e2e-test"))
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
            (((app / Compile) / esbuildStage) / crossTarget).value
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
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % "test"
    )
    .dependsOn(app)
