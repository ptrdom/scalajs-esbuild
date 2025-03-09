package example

import example.facade.node.Fs.mkdtempSync
import example.facade.node.OS.tmpdir
import example.facade.node.Path.join
import example.facade.node._
import example.facade.playwright._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import org.scalatest.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.timers.setTimeout
import scala.scalajs.js.Thenable.Implicits._

import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success

class PlaywrightSpec extends AsyncFreeSpec with Matchers {

  implicit override def executionContext: ExecutionContext = MacrotaskExecutor

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
            // https://playwright.dev/docs/api/class-electron
            // TODO figure out how to `electronApp.evaluate`
            window <- electronApp.firstWindow()
            title <- window.title()
            _ = println(title)
            // taking a screenshot became flaky since Electron 35 with xvfb, simplest workaround is to retry until timeout
            // FIXME figure out how to not need this workaround
            _ <- eventually(window.screenshot(new ScreenshotConfig {
              override val path = "intro.png"
            }))
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

  def eventually[T](f: => Future[T]) = {
    import scala.concurrent.duration._
    val timeout = 15.seconds
    val interval = 150.millis
    val start = System.nanoTime()

    val promise = Promise[Unit]()

    def tryF(): Unit = {
      f
        .onComplete {
          case Success(_) =>
            promise.success(())
          case Failure(ex) =>
            val now = System.nanoTime()
            if ((now - start).nanos < timeout) {
              println(s"Retrying in [$interval]")
              setTimeout(interval) {
                tryF()
              }
            } else {
              promise.failure(
                new RuntimeException(s"Timeout of [$timeout] reached", ex)
              )
            }
        }
    }
    tryF()

    promise.future
  }
}
