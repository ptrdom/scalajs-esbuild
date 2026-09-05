enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "3.9.0"

scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.CommonJSModule)
}

scalaJSUseMainModuleInitializer := true

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.20" % "test"
