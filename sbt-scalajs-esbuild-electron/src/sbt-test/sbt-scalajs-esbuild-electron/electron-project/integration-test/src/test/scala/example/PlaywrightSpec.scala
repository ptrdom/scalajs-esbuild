package example

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

  def withApp(
      block: ElectronApplication => Future[Assertion]
  ): Future[Assertion] = {
    Electron
      .launch(new LaunchConfig {
        override val args =
          js.Array(NodeGlobals.process.env.MAIN_PATH.asInstanceOf[String])
      })
      .flatMap(electronApp =>
        block(electronApp)
          .transform { result =>
            electronApp.close()
            result
          }
      )
  }

  "Work" in withApp { electronApp =>
    // https://playwright.dev/docs/api/class-electron
    for {
      window <- electronApp.firstWindow()
      title <- window.title()
      _ = println(title)
      _ <- window.screenshot(new ScreenshotConfig {
        override val path = "intro.png"
      })
      // TODO finish rewriting example Playwright spec
      _ <- window.isVisible("text='PRELOAD WORKS!'").map(_ shouldBe true)
      _ <- window.isVisible("text='RENDERER WORKS!'").map(_ shouldBe true)
      - <- window
        .evaluate[String](
          "window.getComputedStyle(document.getElementById('css-hook'), '::after')['content']"
        )
        .map(_ shouldBe "\"CSS WORKS!\"")
    } yield succeed
  }
}
