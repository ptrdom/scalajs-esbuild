InputKey[Unit]("html") := {
  import org.openqa.selenium.WebDriver
  import org.openqa.selenium.chrome.ChromeDriver
  import org.openqa.selenium.chrome.ChromeOptions
  import org.scalatest.matchers.should.Matchers
  import org.scalatestplus.selenium.WebBrowser
  import org.scalatest.concurrent.Eventually
  import org.scalatest.concurrent.IntegrationPatience

  val ags = Def.spaceDelimited().parsed.toList

  val port =
    ags match {
      case List(port) => port
      case _          => sys.error("missing arguments")
    }

  val webBrowser = new WebBrowser
    with Matchers
    with Eventually
    with IntegrationPatience {
    val chromeOptions: ChromeOptions = {
      val value = new ChromeOptions
      // arguments recommended by https://itnext.io/how-to-run-a-headless-chrome-browser-in-selenium-webdriver-c5521bc12bf0
      value.addArguments(
        "--disable-gpu",
        "--window-size=1920,1200",
        "--ignore-certificate-errors",
        "--disable-extensions",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--headless",
        "--remote-allow-origins=*"
      )
      value
    }
    implicit val webDriver: WebDriver = new ChromeDriver(chromeOptions)
  }
  import webBrowser._

  eventually {
    go to s"http://localhost:$port"
  }

  eventually {
    find(tagName("h1")).head.text shouldBe "BASIC-WEB-PROJECT WORKS!"
  }

  // should return index instead of 404
  go to s"http://localhost:$port/any"

  eventually {
    find(tagName("h1")).head.text shouldBe "BASIC-WEB-PROJECT WORKS!"
  }

  // should return 404 for html URLs if html file does not exist
  go to s"http://localhost:$port/any.html"

  eventually {
    find(tagName("pre")).head.text shouldBe "HTML file [./any.html] not found"
  }

  ()
}
