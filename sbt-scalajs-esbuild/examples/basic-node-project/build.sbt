enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "2.13.8"

scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.CommonJSModule)
}

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test"
