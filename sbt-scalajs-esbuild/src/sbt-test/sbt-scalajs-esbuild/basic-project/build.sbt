import org.scalajs.jsenv.Input.Script
import org.scalajs.linker.interface.ModuleInitializer
import org.scalajs.linker.interface.unstable
import org.scalajs.testing.adapter.TestAdapterInitializer

enablePlugins(ScalaJSEsbuildPlugin)

scalaVersion := "2.13.8"

scalaJSUseMainModuleInitializer := true

libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % "test"

inConfig(Test) {
  Seq(
    jsEnvInput := Def.taskDyn {
      val stageTask = scalaJSStage.value match {
        case org.scalajs.sbtplugin.Stage.FastOpt => fastLinkJS
        case org.scalajs.sbtplugin.Stage.FullOpt => fullLinkJS
      }
      Def.task {
        (stageTask / esbuildBundle).value

        val jsFileNames = stageTask.value.data match {
          case report: unstable.ReportImpl =>
            val jsFileNames = report.publicModules
              .map { publicModule =>
                publicModule.jsFileName
              }
            jsFileNames
          case unhandled =>
            sys.error(s"Unhandled report type [$unhandled]")
        }

        jsFileNames
          .map((stageTask / esbuildBundle / crossTarget).value / _)
          .map(_.toPath)
          .map(Script)
          .toSeq
      }
    }.value
  )
}
