package example

import example.facade.Lodash
import org.scalajs.dom.Fetch
import org.scalajs.dom
import org.scalajs.dom.document
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.Thenable.Implicits._

@js.native
@JSImport("./styles.css", JSImport.Namespace)
object Style extends js.Object

@js.native
@JSImport("./javascript.svg", JSImport.Default)
object JavascriptLogo extends js.Object

object Main {
  val style = Style

  def main(args: Array[String]): Unit = {
    document.addEventListener(
      "DOMContentLoaded",
      { (_: dom.Event) =>
        setupUI()
      }
    )
  }

  def setupUI(): Unit = {
    val img = document.createElement("img")
    img.setAttribute("src", JavascriptLogo.toString)
    document.body.append(img)

    val cssImage = document.createElement("div")
    cssImage.setAttribute("id", "css-image")
    document.body.append(cssImage)

    Fetch
      .fetch("/hello")
      .flatMap(_.text())
      .foreach { response =>
        val h1 = document.createElement("h1")
        h1.textContent = Lodash.toUpper(response)
        document.body.append(h1)
      }
  }
}
