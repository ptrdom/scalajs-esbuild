//import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
//import org.scalajs.linker.interface.unstable
//import org.scalajs.linker.interface.ModuleInitializer
//import scala.sys.process._
//
//enablePlugins(ScalaJSPlugin)
//
//name := "Scala.js Tutorial"
//scalaVersion := "2.13.6"
//
//scalaJSLinkerConfig ~= {
//  _.withModuleKind(ModuleKind.ESModule)
//}
//
//scalaJSModuleInitializers := Seq(
//  ModuleInitializer
//    .mainMethodWithArgs("example.App1", "main")
//    .withModuleID("app1"),
//  ModuleInitializer
//    .mainMethodWithArgs("example.App2", "main")
//    .withModuleID("app2")
//)
//
//libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "1.2.0"
//
//// Add support for the DOM in `run` and `test`
//jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv()
//
//// uTest settings
//libraryDependencies += "com.lihaoyi" %%% "utest" % "0.7.9" % "test"
//testFrameworks += new TestFramework("utest.runner.Framework")
//
//val bundleTask = taskKey[Unit]("")
//inConfig(Compile) {
//  Seq(
//    bundleTask := {
//      val log = streams.value.log
//
//      val result = fastLinkJS.value
//      val jsFileNames = result.data match {
//        case report: unstable.ReportImpl =>
//          val jsFileNames = report.publicModules
//            .map { publicModule =>
//              publicModule.jsFileName
//            }
//          jsFileNames
//        case unhandled =>
//          sys.error(s"Unhandled report type [$unhandled]")
//      }
//
//      val sourceDir = (fastLinkJS / scalaJSLinkerOutputDirectory).value
//
//      val targetDir = crossTarget.value /
//        "esbuild" /
//        (if (configuration.value == Compile) "main" else "test")
//
//      val command = "node_modules/esbuild/bin/esbuild" :: jsFileNames
//        .map(sourceDir / _)
//        .map(_.absolutePath)
//        .toList ::: "--bundle" :: s"--outdir=${targetDir.absolutePath}" :: Nil
//
//      val exitValue = Process(command)
//        .run(
//          ProcessLogger(
//            out => log.info(out),
//            err => log.error(err)
//          )
//        )
//        .exitValue()
//      if (exitValue != 0) {
//        sys.error(s"Nonzero exit value: $exitValue")
//      } else ()
//    }
//  )
//}
