import complete.DefaultParsers.*
import sbt.ScriptedPlugin.autoImport.scripted
import sbt.scripted.sources.ScriptedSourcesPlugin

inThisBuild(
  List(
    scalaVersion := "2.12.20",
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
  scriptedBufferLog := false
)

lazy val `sbt-scalajs-esbuild` =
  project
    .in(file("sbt-scalajs-esbuild"))
    .enablePlugins(SbtPlugin, ScriptedSourcesPlugin, ExampleVersionPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.1")
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
      addSbtPlugin("com.vmunier" % "sbt-web-scalajs" % "1.3.0"),
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

lazy val `scala-steward-hooks` = project
  .in(file("scala-steward-hooks"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    publish / skip := true,
    scalaVersion := "2.13.16",
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.1",
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
      "org.scalatest" %% "scalatest-shouldmatchers" % "3.2.19",
      "org.scalatestplus" %% "selenium-4-9" % "3.2.16.0",
      "org.seleniumhq.selenium" % "selenium-java" % "4.35.0",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.2.1",
      "org.apache.pekko" %% "pekko-stream" % "1.2.1",
      "org.apache.pekko" %% "pekko-http" % "1.2.0"
    )
  )
