package example.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:path", JSImport.Default)
object Path extends js.Object {
  def join(paths: String*): String = js.native

  val sep: String = js.native
}
