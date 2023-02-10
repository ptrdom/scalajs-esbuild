package example.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

@js.native
@JSGlobalScope
object NodeGlobals extends js.Object {
  val process: Process = js.native
}

@js.native
trait Process extends js.Object {
  val stdin: Stream = js.native
  val stdout: Stream = js.native
}

@js.native
trait Stream extends EventEmitter {
  def resume(): Unit = js.native
}
