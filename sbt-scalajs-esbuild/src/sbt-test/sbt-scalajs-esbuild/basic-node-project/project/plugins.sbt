val sourcePlugins = if (!sys.props.contains("plugin.source")) {
  Seq.empty
} else {
  Seq(
    ProjectRef(
      file("../../../../../../"),
      "sbt-scalajs-esbuild"
    ): ClasspathDep[ProjectReference]
  )
}

lazy val root = (project in file("."))
  .dependsOn(sourcePlugins: _*)

if (sourcePlugins.nonEmpty) {
  Seq.empty
} else {
  val scalaJSEsbuildVersion = sys.props.getOrElse(
    "plugin.version",
    sys.error("'plugin.version' environment variable is not set")
  )
  Seq(
    addSbtPlugin("me.ptrdom" % "sbt-scalajs-esbuild" % scalaJSEsbuildVersion)
  )
}
