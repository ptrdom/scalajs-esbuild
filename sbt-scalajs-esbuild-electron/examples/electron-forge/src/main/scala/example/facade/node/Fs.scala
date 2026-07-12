package example.facade.node

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Fs {
  @js.native
  @JSImport("fs", "mkdtempSync")
  def mkdtempSync(prefix: String): String = js.native
}
