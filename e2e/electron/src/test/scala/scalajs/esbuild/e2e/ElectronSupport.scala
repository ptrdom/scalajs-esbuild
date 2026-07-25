package scalajs.esbuild.e2e

import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions

// Electron-plugin-specific e2e helpers, layered on the plugin-agnostic
// `E2ESupport`: the plugin spawns Electron, so attaching Selenium needs an
// Electron-matched chromedriver, a remote-debugging port injected into the serve
// script, and CDP polling for readiness / respawn detection.
object ElectronSupport {

  /** Copies an electron example and adds a matching `electron-chromedriver` so
    * Selenium can attach to the Electron the plugin spawns.
    */
  def copyExample(name: String): Path = {
    val target =
      E2ESupport.copyExample(E2ESupport.prop("examples.electron"), name)
    addChromedriver(target)
    target
  }

  /** Adds `electron-chromedriver`, pinned to the example's Electron version, to
    * the copy's esbuild package. Its postinstall provides a `chromedriver` that
    * matches Electron's bundled Chromium (Selenium Manager can't supply that).
    */
  private def addChromedriver(projectDir: Path): Unit = {
    val packageJson = projectDir.resolve("esbuild/package.json")
    val original =
      new String(Files.readAllBytes(packageJson), StandardCharsets.UTF_8)
    val electronVersion = """"electron"\s*:\s*"([^"]+)"""".r
      .findFirstMatchIn(original)
      .map(_.group(1))
      .getOrElse(sys.error(s"No electron dependency found in [$packageJson]"))
    val rewritten = original.replaceFirst(
      """("devDependencies"\s*:\s*\{)""",
      "$1" + Matcher.quoteReplacement(
        s"""\n    "electron-chromedriver": "$electronVersion","""
      )
    )
    if (rewritten == original)
      sys.error(s"Could not add electron-chromedriver to [$packageJson]")
    Files.write(packageJson, rewritten.getBytes(StandardCharsets.UTF_8))
  }

  /** Makes the Electron the plugin spawns expose a remote-debugging port for
    * Selenium to attach to, by transforming the copied project's generated
    * `esbuildServeScript` to add the switches to its `spawn(electron, ...)`
    * call. Kept out of the plugin: it's a test concern. The `Compile /
    * fastLinkJS` scope matches how the electron plugin defines the script for
    * the default FastOpt stage; `esbuildServeScript` is in scope via the web
    * plugin's autoImport.
    */
  def writeRemoteDebug(projectDir: Path, debugPort: Int): Unit = {
    val content =
      s"""Compile / fastLinkJS / esbuildServeScript := {
         |  (Compile / fastLinkJS / esbuildServeScript).value.replace(
         |    "'.'], { stdio: 'inherit' }",
         |    "'.', '--remote-debugging-port=$debugPort', '--remote-allow-origins=*'], { stdio: 'inherit' }"
         |  )
         |}
         |""".stripMargin
    Files.write(
      projectDir.resolve("e2e-electron-remote-debug.sbt"),
      content.getBytes(StandardCharsets.UTF_8)
    )
  }

  /** Runs `~esbuildServe` for an electron project (which must have had
    * [[writeRemoteDebug]] applied), ready once Electron's CDP endpoint reports
    * a page target.
    */
  def startWatch(directory: Path, debugPort: Int): Process =
    E2ESupport.spawnWatch(
      directory,
      Map.empty,
      () => cdpPageReady(debugPort),
      300000,
      s"Electron CDP endpoint on port [$debugPort]"
    )

  private def httpGet(url: String, timeoutMillis: Int): Option[String] = {
    val connection =
      URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    connection.setConnectTimeout(timeoutMillis)
    connection.setReadTimeout(timeoutMillis)
    try {
      val stream = connection.getInputStream
      try Some(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
      finally stream.close()
    } catch {
      case _: Throwable => None
    } finally connection.disconnect()
  }

  /** True once Electron's remote-debugging endpoint reports a `page` target. */
  def cdpPageReady(debugPort: Int): Boolean =
    httpGet(s"http://localhost:$debugPort/json", 2000)
      .exists(_.replaceAll("\\s", "").contains("\"type\":\"page\""))

  /** The CDP endpoint's per-process browser id; changes when Electron respawns,
    * so a differing value confirms a main-process restart.
    */
  def cdpBrowserId(debugPort: Int): Option[String] =
    httpGet(s"http://localhost:$debugPort/json/version", 2000).flatMap { body =>
      """"webSocketDebuggerUrl"\s*:\s*"[^"]*/devtools/browser/([^"]+)"""".r
        .findFirstMatchIn(body)
        .map(_.group(1))
    }

  /** The `electron-chromedriver` binary produced by the plugin's esbuild
    * install for a copied project.
    */
  def chromedriver(projectDir: Path): Path = {
    val suffix = if (E2ESupport.isWindows) ".exe" else ""
    projectDir.resolve(
      s"target/scala-2.13/esbuild/main/node_modules/electron-chromedriver/bin/chromedriver$suffix"
    )
  }

  /** Attaches a ChromeDriver to an already-running Electron via its remote
    * debugging port, using the Electron-matched chromedriver (not Selenium
    * Manager, whose Chrome would not match Electron's bundled Chromium). The
    * driver binary is bound to this service rather than the global
    * `webdriver.chrome.driver` property so it can't leak into the web spec's
    * Selenium-Manager driver.
    */
  def newDriver(chromedriver: Path, debugPort: Int): WebDriver = {
    val service = new ChromeDriverService.Builder()
      .usingDriverExecutable(chromedriver.toFile)
      .build()
    val options = new ChromeOptions
    options.addArguments("--remote-allow-origins=*")
    options.setExperimentalOption("debuggerAddress", s"localhost:$debugPort")
    new ChromeDriver(service, options)
  }
}
