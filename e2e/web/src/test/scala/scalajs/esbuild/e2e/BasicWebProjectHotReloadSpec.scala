package scalajs.esbuild.e2e

import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span

// Drives the real `sbt ~esbuildServe` against a copy of `basic-web-project` and
// asserts that sbt's own file-watch hot-swaps a CSS change in place while a
// Scala change triggers a full page reload. A `window` marker distinguishes the
// two: it survives an in-place CSS swap but is cleared by a full reload. The
// edit/assert cycle runs several times over, with distinct values each round, to
// prove the watch keeps rebuilding beyond the first change (not just once).
class BasicWebProjectHotReloadSpec
    extends AnyFreeSpec
    with Matchers
    with Eventually {

  // A reload = sbt detects the change, relinks Scala.js, re-bundles in esbuild,
  // and pushes the SSE event, so give it room.
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(120, Seconds), interval = Span(1, Seconds))

  "basic-web-project hot-reload under a real `sbt ~esbuildServe`" in {
    val projectDir = WebSupport.copyExample("basic-web-project")
    val port = E2ESupport.freePort()
    E2ESupport.writeServerPort(projectDir, port)
    val watch = WebSupport.startWatch(projectDir, port)
    try {
      val driver = WebSupport.newDriver()
      try {
        val stylesCss = projectDir.resolve("esbuild/styles.css")
        val mainScala = projectDir.resolve("src/main/scala/example/Main.scala")

        eventually {
          driver.get(s"http://localhost:$port")
          E2ESupport
            .js(driver)
            .executeScript("return document.readyState") shouldBe "complete"
          E2ESupport.text(driver, "h1") shouldBe "BASIC-WEB-PROJECT WORKS!"
        }

        eventually {
          E2ESupport.css(driver, "body", "background-color") shouldBe
            "rgb(0,191,255)"
        }

        // Each round: a distinct CSS colour (hot-swapped in place) and a distinct
        // h1 text (full reload). Values differ every round so a stale bundle can
        // never satisfy the next assertion. State threads through so each edit
        // targets the value the previous round left behind.
        val rounds = Seq(
          ("red", "rgb(255,0,0)", "updated!", "BASIC-WEB-PROJECT UPDATED!"),
          ("lime", "rgb(0,255,0)", "revised!", "BASIC-WEB-PROJECT REVISED!"),
          ("blue", "rgb(0,0,255)", "changed!", "BASIC-WEB-PROJECT CHANGED!")
        )
        var currentColor = "deepskyblue"
        var currentText = "works!"

        rounds.zipWithIndex.foreach { case ((color, rgb, text, h1), index) =>
          val clue = s"round ${index + 1}: "

          E2ESupport.js(driver).executeScript("window.__marker = 'present';")

          // CSS-only edit: hot-swapped in place, without a full page reload.
          E2ESupport.edit(stylesCss, currentColor, color)
          eventually {
            E2ESupport.css(driver, "body", "background-color") shouldBe rgb
          }
          withClue(
            clue + "marker should survive a CSS hot-swap (no reload): "
          ) {
            E2ESupport
              .js(driver)
              .executeScript("return window.__marker") shouldBe "present"
          }

          // Scala.js edit: triggers a full page reload.
          E2ESupport.edit(mainScala, currentText, text)
          eventually {
            E2ESupport.text(driver, "h1") shouldBe h1
          }
          withClue(clue + "marker should be cleared by a full reload: ") {
            E2ESupport
              .js(driver)
              .executeScript("return window.__marker") shouldBe null
          }

          currentColor = color
          currentText = text
        }
      } finally {
        driver.quit()
      }
    } finally {
      E2ESupport.stopWatch(watch)
      E2ESupport.deleteRecursively(projectDir)
    }
  }
}
