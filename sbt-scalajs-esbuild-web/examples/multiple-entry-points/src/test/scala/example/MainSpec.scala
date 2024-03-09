package example

import org.scalajs.dom.document
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MainSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    document.body.innerHTML = ""
  }

  "Main" should {
    "work with main1" in {
      Main1.setupUI()

      document
        .querySelector("h1")
        .textContent shouldEqual "MULTIPLE-ENTRY-POINTS MAIN1 WORKS!"
    }

    "work with main2" in {
      Main2.setupUI()

      document
        .querySelector("h1")
        .textContent shouldEqual "MULTIPLE-ENTRY-POINTS MAIN2 WORKS!"
    }
  }
}
