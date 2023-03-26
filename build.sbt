inThisBuild(
  List(
    scalaVersion := "2.12.17",
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
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
    versionScheme := Some("semver-spec")
  )
)

lazy val `scalajs-esbuild` = (project in file("."))
  .settings(publish / skip := true)
  .aggregate(`sbt-scalajs-esbuild`, `sbt-scalajs-esbuild-web`)

lazy val commonSettings = Seq(
  scriptedLaunchOpts ++= Seq(
    "-Dplugin.version=" + version.value
  ),
  scriptedBufferLog := false
)

lazy val `sbt-scalajs-esbuild` =
  project
    .in(file("sbt-scalajs-esbuild"))
    .enablePlugins(SbtPlugin, ShadingPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.10.1"),
      libraryDependencies += "org.typelevel" %% "jawn-ast" % "1.4.0",
      shadedModules ++= Set(
        "org.typelevel" %% "jawn-ast",
        "org.typelevel" %% "jawn-parser",
        "org.typelevel" %% "jawn-util"
      ),
      shadingRules ++= Seq(
        ShadingRule
          .moveUnder(
            "org.typelevel.jawn",
            "scalajsesbuild.shaded.org.typelevel.jawn"
          )
      ),
      validNamespaces ++= Set("scalajsesbuild", "sbt")
    )

lazy val `sbt-scalajs-esbuild-web` = project
  .in(file("sbt-scalajs-esbuild-web"))
  .enablePlugins(SbtPlugin)
  .settings(
    commonSettings,
    scriptedDependencies := {
      val () = scriptedDependencies.value
      val () = (`sbt-scalajs-esbuild` / publishLocal).value
    }
  )
  .dependsOn(`sbt-scalajs-esbuild`)
