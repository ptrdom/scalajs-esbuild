enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "2.13.13"

scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.CommonJSModule)
}

scalaJSUseMainModuleInitializer := true

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % "test"
