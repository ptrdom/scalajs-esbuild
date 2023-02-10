package example

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MainSpec extends AnyWordSpec with Matchers {

  "Main" should {
    "work" in {
      Main.testString() shouldEqual "BASIC-PROJECT WORKS!"
    }
  }
}
