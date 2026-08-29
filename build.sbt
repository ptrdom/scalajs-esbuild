import complete.DefaultParsers.*
import sbt.ScriptedPlugin.autoImport.scripted
import sbt.scripted.sources.ScriptedSourcesPlugin

inThisBuild(
  List(
    scalaVersion := "2.12.21",
    organization := "me.ptrdom",
    homepage := Some(url("https://github.com/ptrdom/scalajs-esbuild")),
    licenses := List(License.MIT),
    developers := List(
      Developer(
        "ptrdom",
        "Domantas Petrauskas",
        "dom.petrauskas@gmail.com",
        url("http://ptrdom.me/")
      )
    ),
    versionScheme := Some("semver-spec")
  )
)

lazy val `scalajs-esbuild` = (project in file("."))
  .settings(publish / skip := true)
  .aggregate(
    `sbt-scalajs-esbuild`,
    `sbt-scalajs-esbuild-electron`,
    `sbt-scalajs-esbuild-web`,
    `sbt-web-scalajs-esbuild`,
    `scala-steward-hooks`
  )

lazy val commonSettings = Seq(
  scriptedLaunchOpts ++= Seq(
    "-Dplugin.version=" + version.value
  ),
  scriptedBufferLog := false,
  scriptedBatchExecution := !(isWindows && isCI)
)

lazy val `sbt-scalajs-esbuild` =
  project
    .in(file("sbt-scalajs-esbuild"))
    .enablePlugins(SbtPlugin, ScriptedSourcesPlugin, ExampleVersionPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
    )

lazy val `sbt-scalajs-esbuild-web` = project
  .in(file("sbt-scalajs-esbuild-web"))
  .enablePlugins(SbtPlugin, ScriptedSourcesPlugin, ExampleVersionPlugin)
  .settings(
    commonSettings,
    scriptedDependencies := {
      val () = scriptedDependencies.value
      val () = (`sbt-scalajs-esbuild` / publishLocal).value
    }
  )
  .dependsOn(`sbt-scalajs-esbuild`)

lazy val `sbt-web-scalajs-esbuild` =
  project
    .in(file("sbt-web-scalajs-esbuild"))
    .enablePlugins(SbtPlugin, ScriptedSourcesPlugin, ExampleVersionPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("com.vmunier" % "sbt-web-scalajs" % "1.4.0"),
      scriptedDependencies := {
        val () = scriptedDependencies.value
        val () = (`sbt-scalajs-esbuild-web` / publishLocal).value
      }
    )
    .dependsOn(`sbt-scalajs-esbuild-web`)

lazy val `sbt-scalajs-esbuild-electron` =
  project
    .in(file("sbt-scalajs-esbuild-electron"))
    .enablePlugins(SbtPlugin, ScriptedSourcesPlugin, ExampleVersionPlugin)
    .settings(commonSettings)
    .settings(
      scriptedDependencies := {
        val () = scriptedDependencies.value
        val () = (`sbt-scalajs-esbuild` / publishLocal).value
      }
    )
    .dependsOn(`sbt-scalajs-esbuild-web`)

// workaround for https://github.com/sbt/sbt/issues/7431
InputKey[Unit]("scriptedSequentialPerModule") := Def.inputTaskDyn {
  val args = any.*.parsed.mkString
  Def.taskDyn {
    val projects: Seq[ProjectReference] = `scalajs-esbuild`.aggregate
    Def
      .sequential(
        projects.map(p =>
          Def.taskDyn {
            (p / scripted).?.value match {
              case Some(_) => (p / scripted).toTask(args)
              case None    => Def.task(())
            }
          }
        )
      )
  }
}.evaluated

// publishLocal the plugins under test before the forked e2e JVM runs, so the
// copied example builds against the current source (mirroring how scripted's
// `scriptedDependencies` stages the plugin). Hung off a shared task because
// `test` and `testOnly` are distinct tasks, so both must depend on it for a
// local `<module>/testOnly ...` to see fresh plugin artifacts too.
val e2ePublishPluginsLocal =
  taskKey[Unit]("publishLocal the plugins under test before e2e tests")

// Browser-driven hot-reload e2e tests, one project per plugin module plus a
// shared testkit. Deliberately NOT part of the `scalajs-esbuild` aggregate, so
// the fast `test` / JDK-8 CI check never runs them; they run only via an
// explicit `<module>/test` on browser-enabled CI rows.
lazy val e2eSettings = Seq(
  publish / skip := true,
  scalaVersion := "2.13.18",
  Test / fork := true,
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.20" % Test,
    "org.scalatest" %% "scalatest-shouldmatchers" % "3.2.20" % Test
  )
)

// Shared machinery (`E2ESupport`) lives in main scope so each module's specs
// can depend on it. Selenium is a compile dependency here and reaches the
// per-module projects transitively.
lazy val `e2e-testkit` = project
  .in(file("e2e/testkit"))
  .settings(
    publish / skip := true,
    scalaVersion := "2.13.18",
    libraryDependencies +=
      "org.seleniumhq.selenium" % "selenium-java" % "4.48.0"
  )

lazy val `e2e-web` = project
  .in(file("e2e/web"))
  .dependsOn(`e2e-testkit`)
  .settings(e2eSettings)
  .settings(
    Test / javaOptions ++= Seq(
      s"-Dplugin.version=${version.value}",
      s"-Dplugin.channel=${sys.env.getOrElse("E2E_CHANNEL", "snapshot")}",
      s"-Dexamples.web=${((`sbt-scalajs-esbuild-web` / baseDirectory).value / "examples").absolutePath}"
    ),
    e2ePublishPluginsLocal := {
      (`sbt-scalajs-esbuild` / publishLocal).value
      (`sbt-scalajs-esbuild-web` / publishLocal).value
    },
    Test / test := (Test / test).dependsOn(e2ePublishPluginsLocal).value,
    Test / testOnly :=
      (Test / testOnly).dependsOn(e2ePublishPluginsLocal).evaluated
  )

lazy val `e2e-electron` = project
  .in(file("e2e/electron"))
  .dependsOn(`e2e-testkit`)
  .settings(e2eSettings)
  .settings(
    Test / javaOptions ++= Seq(
      s"-Dplugin.version=${version.value}",
      s"-Dplugin.channel=${sys.env.getOrElse("E2E_CHANNEL", "snapshot")}",
      s"-Dexamples.electron=${((`sbt-scalajs-esbuild-electron` / baseDirectory).value / "examples").absolutePath}"
    ),
    e2ePublishPluginsLocal := {
      (`sbt-scalajs-esbuild` / publishLocal).value
      (`sbt-scalajs-esbuild-web` / publishLocal).value
      (`sbt-scalajs-esbuild-electron` / publishLocal).value
    },
    Test / test := (Test / test).dependsOn(e2ePublishPluginsLocal).value,
    Test / testOnly :=
      (Test / testOnly).dependsOn(e2ePublishPluginsLocal).evaluated
  )

lazy val `scala-steward-hooks` = project
  .in(file("scala-steward-hooks"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    publish / skip := true,
    scalaVersion := "2.13.18",
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.1",
      "org.scalatest" %% "scalatest" % "3.2.20" % "test",
      "org.scalatest" %% "scalatest-shouldmatchers" % "3.2.20",
      "org.scalatestplus" %% "selenium-4-12" % "3.2.17.0",
      "org.seleniumhq.selenium" % "selenium-java" % "4.48.0",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.7.0",
      "org.apache.pekko" %% "pekko-stream" % "1.7.0",
      "org.apache.pekko" %% "pekko-http" % "1.4.0",
      "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1"
    )
  )

lazy val isWindows =
  sys.props.get("os.name").exists(_.toLowerCase.contains("win"))
lazy val isCI = sys.env.get("CI").contains("true")
