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
      sys.env.get("E2E_TEST_BROWSER").map(_.toLowerCase).getOrElse("chrome") match {
        case "chrome" =>
          val options = new ChromeOptions
          options.addArguments(arguments: _*)
          new ChromeDriver(options)
        case "firefox" =>
          val options = new FirefoxOptions
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
      }

      eventually {
        find(tagName("h1")).head.text shouldBe "BASIC-PROJECT-SBT-WEB WORKS!"
      }
    } withClue s"Page source:\n[$pageSource]"
  } finally {
    webBrowser.webDriver.quit()
  }

  ()
}
