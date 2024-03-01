val sourcePlugins = sys.props
  .get("plugin.version")
  .map { version =>
    println(s"Using plugin(s) version [$version]")
    Seq.empty
  }
  .getOrElse {
    println("Building plugin(s) from source")
    Seq(
      ProjectRef(
        file("../../../../../../"),
        "sbt-scalajs-esbuild-electron"
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
    addSbtPlugin(
      "me.ptrdom" % "sbt-scalajs-esbuild-electron" % scalaJSEsbuildVersion
    )
  )
}
