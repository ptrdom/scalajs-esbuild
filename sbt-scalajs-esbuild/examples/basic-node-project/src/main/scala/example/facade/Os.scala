package example.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:os", JSImport.Default)
object Os extends js.Object {
  def tmpdir(): String = js.native
}
