InputKey[Unit]("html") := {
  import org.openqa.selenium.WebDriver
  import org.openqa.selenium.chrome.ChromeDriver
  import org.openqa.selenium.chrome.ChromeOptions
  import org.openqa.selenium.firefox.FirefoxDriver
  import org.openqa.selenium.firefox.FirefoxOptions
  import org.scalatest.matchers.should.Matchers
  import org.scalatestplus.selenium.WebBrowser
  import org.scalatest.concurrent.Eventually
  import org.scalatest.concurrent.IntegrationPatience
  import org.scalatest.AppendedClues.convertToClueful

  val ags = Def.spaceDelimited().parsed.toList

  val port =
    ags match {
      case List(port) => port
      case _          => sys.error("missing arguments")
    }

  // Pinned so Selenium Manager fetches a deterministic browser+driver pair
  // both locally and in CI, instead of resolving against whatever is on PATH.
  val chromeForTestingVersion = "151.0.7922.47"
  val firefoxVersion = "153.0"

  val webBrowser = new WebBrowser
    with Matchers
    with Eventually
    with IntegrationPatience {
    implicit val webDriver: WebDriver = {
      // arguments recommended by https://itnext.io/how-to-run-a-headless-chrome-browser-in-selenium-webdriver-c5521bc12bf0
      val arguments = Seq(
        "--disable-gpu",
        "--window-size=1920,1200",
        "--ignore-certificate-errors",
        "--disable-extensions",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--headless"
      )
      sys.env
        .get("E2E_TEST_BROWSER")
        .map(_.toLowerCase)
        .getOrElse("chrome") match {
        case "chrome" =>
          val options = new ChromeOptions
          options.setBrowserVersion(chromeForTestingVersion)
          options.addArguments(arguments: _*)
          new ChromeDriver(options)
        case "firefox" =>
          val options = new FirefoxOptions
          options.setBrowserVersion(firefoxVersion)
          options.addArguments(arguments: _*)
          new FirefoxDriver(options)
        case unhandled =>
          sys.error(s"Unhandled browser [$unhandled]")
      }
    }
  }
  import webBrowser._

  try {
    {
      eventually {
        go to s"http://localhost:$port"

        find(tagName("h1")).head.text shouldBe "BASIC-PROJECT-SBT-WEB WORKS!"
      }
    } withClue {
      val maybePageSource =
        try pageSource
        catch {
          case t: Throwable =>
            s"<pageSource threw ${t.getClass.getName}: ${t.getMessage}>"
        }
      s"Page source:\n[$maybePageSource]"
    }
  } finally {
    webBrowser.webDriver.quit()
  }

  ()
}
