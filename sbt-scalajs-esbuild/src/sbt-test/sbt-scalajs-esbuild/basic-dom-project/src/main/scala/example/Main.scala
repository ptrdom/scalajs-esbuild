package example

import example.facade.Lodash
import org.scalajs.dom
import org.scalajs.dom.document

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("./styles.css", JSImport.Namespace)
object Style extends js.Object

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
    val h1 = document.createElement("h1")
    h1.textContent = testString()
    document.body.append(h1)
  }

  def testString(): String = {
    Lodash.toUpper("basic-dom-project works!")
  }
}
