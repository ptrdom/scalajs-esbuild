package example

import example.facade.Lodash
import org.scalajs.dom
import org.scalajs.dom.document

object Main2 {
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
    val h1 = document.createElement("h1")
    h1.textContent = testString()
    document.body.append(h1)
  }

  def testString(): String = {
    Lodash.toUpper("multiple-entry-points main2 works!")
  }
}
