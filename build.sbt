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
  .aggregate(`sbt-scalajs-esbuild`)

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
