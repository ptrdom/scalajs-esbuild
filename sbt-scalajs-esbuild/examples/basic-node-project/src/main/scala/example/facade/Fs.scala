package example.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:fs", JSImport.Default)
object Fs extends js.Object {
  def mkdtempSync(paths: String): String = js.native

  def writeFileSync(file: String, data: String, encoding: String): Unit =
    js.native

  def readFileSync(path: String, encoding: String): String = js.native
}
