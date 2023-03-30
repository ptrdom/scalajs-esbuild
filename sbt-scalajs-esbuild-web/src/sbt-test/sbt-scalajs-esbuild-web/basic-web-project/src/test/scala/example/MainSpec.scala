package example

import org.scalajs.dom.document
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MainSpec extends AnyWordSpec with Matchers {

  "Main" should {
    "work" in {
      Main.setupUI()

      document
        .querySelector("h1")
        .textContent shouldEqual "BASIC-DOM-PROJECT WORKS!"
    }
  }
}
