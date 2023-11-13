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
      addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.14.0")
    )

lazy val `sbt-scalajs-esbuild-web` = project
  .in(file("sbt-scalajs-esbuild-web"))
  .enablePlugins(SbtPlugin, ShadingPlugin)
  .settings(
    commonSettings,
    libraryDependencies += "org.typelevel" %% "jawn-ast" % "1.5.1",
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
    },
    // workaround for https://github.com/coursier/sbt-shading/issues/39
    packagedArtifacts := {
      val sbtV = (pluginCrossBuild / sbtBinaryVersion).value
      val scalaV = scalaBinaryVersion.value
      val packagedArtifactsV = packagedArtifacts.value
      val nameV = name.value

      val (legacyArtifact, legacyFile) = packagedArtifactsV
        .find { case (a, _) =>
          a.`type` == "jar" && a.name == nameV
        }
        .getOrElse(sys.error("Legacy jar not found"))
      val mavenArtifact =
        legacyArtifact.withName(nameV + s"_${scalaV}_$sbtV")
      val mavenFile = new File(
        legacyFile.getParentFile,
        legacyFile.name.replace(legacyArtifact.name, mavenArtifact.name)
      )
      IO.copyFile(legacyFile, mavenFile)

      packagedArtifactsV + (mavenArtifact -> mavenFile)
    }
  )
  .dependsOn(`sbt-scalajs-esbuild`)

lazy val `sbt-web-scalajs-esbuild` =
  project
    .in(file("sbt-web-scalajs-esbuild"))
    .enablePlugins(SbtPlugin)
    .settings(commonSettings)
    .settings(
      addSbtPlugin("com.vmunier" % "sbt-web-scalajs" % "1.2.0"),
      scriptedDependencies := {
        val () = scriptedDependencies.value
        val () = (`sbt-scalajs-esbuild-web` / publishLocal).value
      }
    )
    .dependsOn(`sbt-scalajs-esbuild-web`)

TaskKey[Unit]("scriptedSequentialPerModule") := {
  Def.taskDyn {
    val projects: Seq[ProjectReference] = `scalajs-esbuild`.aggregate
    Def
      .sequential(
        projects.map(p => Def.taskDyn((p / scripted).toTask("")))
      )
  }.value
}
