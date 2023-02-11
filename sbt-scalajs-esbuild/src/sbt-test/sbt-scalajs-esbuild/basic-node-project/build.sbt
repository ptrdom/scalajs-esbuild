enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "2.13.8"

scalaJSUseMainModuleInitializer := true

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
