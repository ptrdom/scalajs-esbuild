package scalajsesbuild

import sbt.AutoPlugin
import sbt.Plugins

object ScalaJSEsbuildElectronPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaJSEsbuildWebPlugin

  object autoImport {

  }
}
