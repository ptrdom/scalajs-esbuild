package example

import org.scalajs.dom
import org.scalajs.dom.document

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("./styles.css", JSImport.Namespace)
object Style extends js.Object

/** This file is loaded via the &lt;script&gt; tag in the index.html file and
  * will be executed in the renderer process for that window. No Node.js APIs
  * are available in this process because `nodeIntegration` is turned off and
  * `contextIsolation` is turned on. Use the contextBridge API in `preload.js`
  * to expose Node.js functionality from the main process.
  */
object Renderer extends App {
  val style = Style

  document.addEventListener(
    "DOMContentLoaded",
    { (_: dom.Event) =>
      val h1 = document.createElement("h1")
      h1.textContent = "RENDERER WORKS!"
      document.body.append(h1)
    }
  )
}
