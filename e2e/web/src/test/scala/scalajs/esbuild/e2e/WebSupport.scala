package scalajs.esbuild.e2e

import java.nio.file.Path

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions

// Web-plugin-specific e2e helpers, layered on the plugin-agnostic `E2ESupport`.
object WebSupport {

  // Pinned so Selenium Manager fetches a deterministic browser+driver pair both
  // locally and in CI, instead of resolving against whatever is on PATH.
  private val chromeForTestingVersion = "151.0.7922.47"
  private val firefoxVersion = "153.0"

  /** Copies a web example, ready to run `~esbuildServe` against. */
  def copyExample(name: String): Path =
    E2ESupport.copyExample(E2ESupport.prop("examples.web"), name)

  /** Runs `~esbuildServe`, ready once the dev server on `port` accepts
    * connections.
    */
  def startWatch(directory: Path, port: Int): Process =
    E2ESupport.spawnWatch(
      directory,
      Map.empty,
      () => E2ESupport.tcpReachable(port),
      240000,
      s"Dev server on port [$port]"
    )

  def newDriver(): WebDriver = {
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
