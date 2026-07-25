package scalajs.esbuild.e2e

import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span

// Drives the real `sbt ~esbuildServe` against a copy of the electron
// `basic-project` and asserts hot-reload across all three Electron process
// types. A renderer edit and a CSS edit reload the window in place; a preload
// edit reloads it too (re-running preload); and a main-process edit respawns
// Electron - dropping the debug connection - after which Selenium re-attaches
// to the new process and sees the app rebuilt from the edited sources. The
// edit/assert cycle runs several rounds with distinct values each time, to
// prove the watch keeps rebuilding and Electron keeps re-spawning past the
// first change.
class BasicElectronProjectHotReloadSpec
    extends AnyFreeSpec
    with Matchers
    with Eventually {

  // A reload/respawn = sbt relinks Scala.js, esbuild re-bundles, Electron
  // reloads or restarts, so give it room.
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(180, Seconds), interval = Span(1, Seconds))

  "basic-project hot-reload under a real `sbt ~esbuildServe`" in {
    val projectDir = E2ESupport.copyElectronExample("basic-project")
    val serverPort = E2ESupport.freePort()
    E2ESupport.writeServerPort(projectDir, serverPort)
    val debugPort = E2ESupport.freePort()
    E2ESupport.writeElectronRemoteDebug(projectDir, debugPort)
    val watch = E2ESupport.startElectronWatch(projectDir, debugPort)
    try {
      val chromedriver = E2ESupport.electronChromedriver(projectDir)
      var driver = E2ESupport.newElectronDriver(chromedriver, debugPort)
      try {
        val renderer =
          projectDir.resolve("src/main/scala/example/Renderer.scala")
        val preload = projectDir.resolve("src/main/scala/example/Preload.scala")
        val main = projectDir.resolve("src/main/scala/example/Main.scala")
        val styles = projectDir.resolve("esbuild/styles.css")

        def cssContent(expected: String): String = "\"" + expected + "\""

        // Initial load: renderer, preload and CSS all present in one window.
        eventually {
          E2ESupport.texts(driver, "h1") should contain allOf (
            "RENDERER WORKS!",
            "PRELOAD WORKS!"
          )
          E2ESupport
            .pseudoContent(driver, "#css-hook", "::after") shouldBe
            cssContent("CSS WORKS!")
        }

        // Each round edits all three process types with distinct values, so a
        // stale bundle can never satisfy the next round, and repeats to prove
        // the watch keeps rebuilding (and Electron keeps re-spawning) past the
        // first change. State threads through: each edit targets what the
        // previous round left behind. The `width` bump is an arbitrary
        // main-process change that forces a respawn.
        val rounds = Seq(
          ("UPDATED", "800", "900"),
          ("REVISED", "900", "1000"),
          ("CHANGED", "1000", "1100")
        )
        var currentRenderer = "RENDERER WORKS!"
        var currentPreload = "PRELOAD WORKS!"
        var currentCss = "CSS WORKS!"

        rounds.zipWithIndex.foreach {
          case ((word, fromWidth, toWidth), index) =>
            val clue = s"round ${index + 1}: "
            val newRenderer = s"RENDERER $word!"
            val newPreload = s"PRELOAD $word!"
            val newCss = s"CSS $word!"

            // Renderer edit: the window reloads in place (same process).
            E2ESupport.edit(renderer, currentRenderer, newRenderer)
            withClue(clue) {
              eventually {
                E2ESupport.texts(driver, "h1") should contain(newRenderer)
              }
            }

            // CSS edit: hot-swapped into the same window.
            E2ESupport.edit(styles, currentCss, newCss)
            withClue(clue) {
              eventually {
                E2ESupport
                  .pseudoContent(driver, "#css-hook", "::after") shouldBe
                  cssContent(newCss)
              }
            }

            // Preload edit: the preload rebuild reloads the renderer,
            // re-running preload (still the same process).
            E2ESupport.edit(preload, currentPreload, newPreload)
            withClue(clue) {
              eventually {
                E2ESupport.texts(driver, "h1") should contain(newPreload)
              }
            }

            // Main edit: the main-process rebuild respawns Electron. Detect the
            // new process by a changed CDP browser id, then re-attach.
            val before = E2ESupport.cdpBrowserId(debugPort)
            before shouldBe defined
            E2ESupport.edit(main, s"width = $fromWidth", s"width = $toWidth")
            withClue(clue) {
              eventually {
                E2ESupport
                  .cdpBrowserId(debugPort) should (be(
                  defined
                ) and not be before)
              }
              eventually {
                E2ESupport.cdpPageReady(debugPort) shouldBe true
              }
            }
            try driver.quit()
            catch { case _: Throwable => () }
            driver = E2ESupport.newElectronDriver(chromedriver, debugPort)
            // The re-attached window shows the app rebuilt from the round's
            // edited sources.
            withClue(clue) {
              eventually {
                E2ESupport.texts(driver, "h1") should contain allOf (
                  newRenderer,
                  newPreload
                )
                E2ESupport
                  .pseudoContent(driver, "#css-hook", "::after") shouldBe
                  cssContent(newCss)
              }
            }

            currentRenderer = newRenderer
            currentPreload = newPreload
            currentCss = newCss
        }
      } finally {
        try driver.quit()
        catch { case _: Throwable => () }
      }
    } finally {
      E2ESupport.stopWatch(watch)
      E2ESupport.deleteRecursively(projectDir)
    }
  }
}
