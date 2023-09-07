inThisBuild(
  List(
    scalaVersion := "2.12.18",
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
  .aggregate(
    `sbt-scalajs-esbuild`,
    `sbt-scalajs-esbuild-web`,
    `sbt-web-scalajs-esbuild`
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
    .enablePlugins(SbtPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.10.1")
    )

lazy val `sbt-scalajs-esbuild-web` = project
  .in(file("sbt-scalajs-esbuild-web"))
  .enablePlugins(SbtPlugin, ShadingPlugin)
  .settings(
    commonSettings,
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
    validNamespaces ++= Set("scalajsesbuild", "sbt"),
    scriptedDependencies := {
      val () = scriptedDependencies.value
      val () = (`sbt-scalajs-esbuild` / publishLocal).value
    }
  )
  .dependsOn(`sbt-scalajs-esbuild`)

lazy val `sbt-web-scalajs-esbuild` =
  project
    .in(file("sbt-web-scalajs-esbuild"))
    .enablePlugins(SbtPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("com.vmunier" % "sbt-web-scalajs" % "1.1.0"),
      scriptedDependencies := {
        val () = scriptedDependencies.value
        val () = (`sbt-scalajs-esbuild-web` / publishLocal).value
      }
    )
    .dependsOn(`sbt-scalajs-esbuild-web`)
