// Merged into every scripted test (see the `.sources` files). Scala.js 1.23+
// needs JDK 17, so on older JDKs force sbt-scalajs down to the last release
// that runs there, the same override the README gives JDK 8 users. It must be
// built with sbtPluginExtra: a plain module ID does not match a plugin.
if (sys.props("java.specification.version").stripPrefix("1.").toInt >= 17)
  Seq.empty
else
  Seq(
    dependencyOverrides += Defaults.sbtPluginExtra(
      "org.scala-js" % "sbt-scalajs" % "1.22.0", // scala-steward:off
      (pluginCrossBuild / sbtBinaryVersion).value,
      scalaBinaryVersion.value
    )
  )
