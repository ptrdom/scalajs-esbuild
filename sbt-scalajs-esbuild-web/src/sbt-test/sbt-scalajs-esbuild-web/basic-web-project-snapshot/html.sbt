InputKey[Unit]("html") := {
  val log = streams.value.log

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
  import org.scalatest.Inside

  val ags = Def.spaceDelimited().parsed.toList

  val port =
    ags match {
      case List(port) => port
      case _          => sys.error("missing arguments")
    }

  // Pinned so Selenium Manager fetches a deterministic browser+driver pair
  // both locally and in CI, instead of resolving against whatever is on PATH.
  val chromeForTestingVersion = "149.0.7827.22"
  val firefoxVersion = "151.0.1"

  val webBrowser = new WebBrowser
    with Matchers
    with Eventually
    with IntegrationPatience
    with Inside {
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

        inside(find(tagName("pre"))) {
          case None =>
            succeed
          case Some(element) =>
            element.text should not include regex(
              "META file \\[.*\\] not found"
            )
        }
      }

      eventually {
        find(tagName("h1")).head.text shouldBe "BASIC-WEB-PROJECT WORKS!"
      }

      eventually {
        // should return index instead of 404
        go to s"http://localhost:$port/any"

        find(tagName("h1")).head.text shouldBe "BASIC-WEB-PROJECT WORKS!"
      }

      eventually {
        // should return 404 for html URLs if html file does not exist
        go to s"http://localhost:$port/any.html"

        pageSource should include("HTML file [./any.html] not found")
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
