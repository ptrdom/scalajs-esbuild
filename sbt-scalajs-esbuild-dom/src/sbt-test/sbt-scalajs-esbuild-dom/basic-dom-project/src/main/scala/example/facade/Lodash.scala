package example.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@JSImport("lodash", JSImport.Namespace)
@js.native
object Lodash extends js.Object {
  def toUpper(string: String): String = js.native
}
