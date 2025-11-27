enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "2.13.18"

scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.CommonJSModule)
}

scalaJSUseMainModuleInitializer := true

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.19" % "test"
