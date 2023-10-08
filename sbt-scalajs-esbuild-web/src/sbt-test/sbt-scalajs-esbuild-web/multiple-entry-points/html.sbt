import scala.util.control.NonFatal

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

  try {
    eventually {
      go to s"http://localhost:$port"
    }

    eventually {
      find(
        tagName("pre")
      ).head.text shouldBe "Multiple html entry points defined, unable to pick single root"
    }

    go to s"http://localhost:$port/index1.html"

    eventually {
      find(
        tagName("h1")
      ).head.text shouldBe "MULTIPLE-ENTRY-POINTS MAIN1 WORKS!"
    }

    go to s"http://localhost:$port/index2.html"

    eventually {
      find(
        tagName("h1")
      ).head.text shouldBe "MULTIPLE-ENTRY-POINTS MAIN2 WORKS!"
    }

    // do not redirect 404s to index if there are multiple html entry points
    go to s"http://localhost:$port/any"

    eventually {
      find(tagName("pre")).head.text shouldBe "404 - Not Found"
    }
  } catch {
    case NonFatal(ex) =>
      println(s"Captured page source:\n$pageSource")
      throw ex
  }

  ()
}
