package scalajs.esbuild

import org.scalajs.linker.interface.Report
import org.scalajs.linker.interface.unstable

package object electron {

  private[electron] def extractEntryPointsByProcess(
      report: Report,
      electronProcessConfiguration: EsbuildElectronProcessConfiguration
  ) = {
    report match {
      case report: unstable.ReportImpl =>
        val mainModuleEntryPoint = report.publicModules
          .find(
            _.moduleID == electronProcessConfiguration.mainModuleID
          )
          .getOrElse(
            sys.error(
              s"Main module [$electronProcessConfiguration.mainModuleID] not found in Scala.js modules"
            )
          )
          .jsFileName
        val preloadModuleEntryPoints =
          electronProcessConfiguration.preloadModuleIDs
            .map(moduleID =>
              report.publicModules
                .find(
                  _.moduleID == moduleID
                )
                .getOrElse(
                  sys.error(
                    s"Preload module [$electronProcessConfiguration.mainModuleID] not found in Scala.js modules"
                  )
                )
                .jsFileName
            )
        val rendererModuleEntryPoints =
          electronProcessConfiguration.rendererModuleIDs
            .map(moduleID =>
              report.publicModules
                .find(
                  _.moduleID == moduleID
                )
                .getOrElse(
                  sys.error(
                    s"Renderer module [$electronProcessConfiguration.mainModuleID] not found in Scala.js modules"
                  )
                )
                .jsFileName
            )
        (
          mainModuleEntryPoint,
          preloadModuleEntryPoints,
          rendererModuleEntryPoints
        )
      case unhandled =>
        sys.error(s"Unhandled report type [$unhandled]")
    }
  }
}
