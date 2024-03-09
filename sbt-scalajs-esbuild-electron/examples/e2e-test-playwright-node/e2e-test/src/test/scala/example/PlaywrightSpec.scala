package example

import example.facade.node.Fs.mkdtempSync
import example.facade.node.OS.tmpdir
import example.facade.node.Path.join
import example.facade.node._
import example.facade.playwright._
import org.scalatest.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits._

class PlaywrightSpec extends AsyncFreeSpec with Matchers {

  implicit override def executionContext =
    JSExecutionContext.queue

  "Work" in {
    Electron
      .launch(new LaunchConfig {
        override val args =
          js.Array(
            NodeGlobals.process.env.MAIN_PATH.asInstanceOf[String],
            s"--user-data-dir=${mkdtempSync(join(tmpdir(), "electron-app-user-data-directory-"))}"
          )
      })
      .flatMap(electronApp =>
        {
          for {
            // https://playwright.dev/docs/api/class-electron\
            // TODO figure out how to `electronApp.evaluate`
            window <- electronApp.firstWindow()
            title <- window.title()
            _ = println(title)
            _ <- window.screenshot(new ScreenshotConfig {
              override val path = "intro.png"
            })
            _ <- window.isVisible("text='PRELOAD WORKS!'").map(_ shouldBe true)
            _ <- window.isVisible("text='RENDERER WORKS!'").map(_ shouldBe true)
            - <- window
              .evaluate[String](
                "window.getComputedStyle(document.getElementById('css-hook'), '::after')['content']"
              )
              .map(_ shouldBe "\"CSS WORKS!\"")
          } yield succeed
        }
          .transform { result =>
            electronApp.close()
            result
          }
      )
  }
}
