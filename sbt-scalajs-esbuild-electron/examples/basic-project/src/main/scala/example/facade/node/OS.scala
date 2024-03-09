package example.facade.node

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object OS {
  @js.native
  @JSImport("os", "tmpdir")
  def tmpdir(): String = js.native
}
