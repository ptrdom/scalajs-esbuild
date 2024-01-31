package example

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.selenium.WebBrowser

import scala.annotation.tailrec
import scala.sys.process._

class SeleniumSpec extends AnyFreeSpec with Matchers {

  val targetDirectory = {
    // test can be executed by IntelliJ Run/Debug configurations and from sbt,
    // working directory is not the same for both of them,
    // so we check both of them if either exists
    val scalaTestRunnerPath = "../"
    val sbtRunnerPath = ""
    val targetRelativePath = "/app/target/scala-2.13/esbuild/main"

    @tailrec
    def resolve(toTry: List[String]): File = {
      toTry match {
        case ::(head, next) =>
          val maybeTargetDirectory = Paths
            .get(
              new File(head).getAbsolutePath,
              targetRelativePath
            )
            .toFile
          if (new File(maybeTargetDirectory, "package.json").exists()) {
            maybeTargetDirectory
          } else {
            resolve(next)
          }
        case Nil =>
          sys.error("Target directory for esbuild project was not resolved")
      }
    }
    resolve(List(scalaTestRunnerPath, sbtRunnerPath))
  }
  val debugPort = 9222

  System.setProperty(
    "webdriver.chrome.driver",
    Paths
      .get(
        targetDirectory.getAbsolutePath,
        "node_modules/electron-chromedriver/bin/chromedriver.exe"
      )
      .normalize()
      .toString
  )

  "Work" in {
    val userDataDir =
      Files.createTempDirectory("electron-app-user-data-directory")
    val process = Process(
      "node" ::
        "./node_modules/electron/cli" ::
        "./out/main.js" ::
        s"--remote-debugging-port=$debugPort" ::
        "--remote-allow-origins=*" ::
        s"--user-data-dir=${userDataDir.toAbsolutePath}" ::
        Nil,
      targetDirectory
    ).run
    try {
      val options = new ChromeOptions()
      options.addArguments("--remote-allow-origins=*")
      options.setExperimentalOption("debuggerAddress", s"localhost:$debugPort")
      implicit val webDriver: WebDriver = new ChromeDriver(options)
      try {
        new WebBrowser {
          pageTitle shouldBe "Hello World!"
          find(xpath("//h1[text()='PRELOAD WORKS!']")) shouldBe defined
          find(xpath("//h1[text()='RENDERER WORKS!']")) shouldBe defined
          executeScript(
            "return window.getComputedStyle(document.getElementById('css-hook'), '::after')['content']"
          ).asInstanceOf[String] shouldBe "\"CSS WORKS!\""
        }
      } finally {
        webDriver.quit()
      }
    } finally {
      process.destroy()
    }
  }
}
