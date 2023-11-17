package scalajsesbuild

import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt.AutoPlugin
import sbt.Plugins

object ScalaJSEsbuildElectronPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaJSPlugin

  object autoImport {

  }
}
