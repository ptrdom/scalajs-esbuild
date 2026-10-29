package scalajs.esbuild.e2e

import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Matcher

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions

// Electron-plugin-specific e2e helpers, layered on the plugin-agnostic
// `E2ESupport`: the plugin spawns Electron, so attaching Selenium needs an
// Electron-matched chromedriver, remote debugging enabled through the serve
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

  /** Adds `electron-chromedriver` to the copy's esbuild package. Its
    * postinstall provides a `chromedriver` that matches Electron's bundled
    * Chromium (Selenium Manager can't supply that). The example's `yauzl`
    * override is copied too, so that postinstall extracts the whole zip on Node 26.
    */
  private def addChromedriver(projectDir: Path): Unit = {
    val packageJson = projectDir.resolve("esbuild/package.json")
    val original =
      new String(Files.readAllBytes(packageJson), StandardCharsets.UTF_8)
    def insertAfter(content: String, regex: String, insertion: String) = {
      val rewritten =
        content.replaceFirst(regex, "$1" + Matcher.quoteReplacement(insertion))
      if (rewritten == content)
        sys.error(s"Could not add electron-chromedriver to [$packageJson]")
      rewritten
    }
    val withDependency = insertAfter(
      original,
      """("devDependencies"\s*:\s*\{)""",
      s"""\n    "electron-chromedriver": "$chromedriverVersion","""
    )
    val rewritten = insertAfter(
      withDependency,
      """^(\s*\{)""",
      s"""\n  "overrides": { "yauzl": "$yauzlVersion" },"""
    )
    Files.write(packageJson, rewritten.getBytes(StandardCharsets.UTF_8))
  }

  private lazy val seleniumExamplePackageJson: (Path, String) = {
    val packageJson = Paths.get(
      E2ESupport.prop("examples.electron"),
      "e2e-test-selenium-jvm/app/esbuild/package.json"
    )
    (
      packageJson,
      new String(Files.readAllBytes(packageJson), StandardCharsets.UTF_8)
    )
  }

  private def seleniumExampleVersion(name: String): String = {
    val (packageJson, content) = seleniumExamplePackageJson
    ("\"" + java.util.regex.Pattern.quote(name) + """"\s*:\s*"([^"]+)"""").r
      .findFirstMatchIn(content)
      .map(_.group(1))
      .getOrElse(sys.error(s"No $name version found in [$packageJson]"))
  }

  /** The `electron-chromedriver` version pinned by the `e2e-test-selenium-jvm`
    * example. Not derived from the example's Electron version: chromedriver
    * releases lag Electron's and sometimes skip patch versions, so an exact
    * match may not exist yet. Renovate keeps this pin on a published version
    * alongside Electron (the two are grouped), and chromedriver only needs to
    * match Electron's Chromium major.
    */
  private lazy val chromedriverVersion: String =
    seleniumExampleVersion("electron-chromedriver")

  /** The `yauzl` override from the `e2e-test-selenium-jvm` example. */
  private lazy val yauzlVersion: String = seleniumExampleVersion("yauzl")

  /** Makes the Electron the plugin spawns expose a remote-debugging port for
    * Selenium to attach to, by transforming the copied project's generated
    * `esbuildServeScript` to add the switches to its `spawn(electron, ...)`
    * call. Kept out of the plugin: it's a test concern. The `Compile /
    * fastLinkJS` scope matches how the electron plugin defines the script for
    * the default FastOpt stage; `esbuildServeScript` is in scope via the web
    * plugin's autoImport.
    *
    * Port 0 lets each spawned Electron pick a free port, which [[startWatch]]
    * picks up from its output. A fixed port would have to be rebound by the
    * Electron respawned on a main-process change, which Windows and macOS
    * intermittently refuse while the killed one's connections linger in
    * TIME_WAIT. The port is not read from the `DevToolsActivePort` file
    * Chromium writes to the user data directory: Windows intermittently refuses
    * a respawned Electron the rewrite of that file ("being used by another
    * process"), and Chromium never retries.
    */
  def writeRemoteDebug(projectDir: Path): Unit = {
    val content =
      s"""Compile / fastLinkJS / esbuildServeScript := {
         |  (Compile / fastLinkJS / esbuildServeScript).value.replace(
         |    "'.'], { stdio: 'inherit' }",
         |    "'.', '--remote-debugging-port=0', '--remote-allow-origins=*'], { stdio: 'inherit' }"
         |  )
         |}
         |""".stripMargin
    Files.write(
      projectDir.resolve("e2e-electron-remote-debug.sbt"),
      content.getBytes(StandardCharsets.UTF_8)
    )
  }

  /** A running `~esbuildServe` for an electron project, tracking the
    * remote-debugging port of the Electron it last spawned from the
    * `DevTools listening on ws://host:port/...` line Chromium prints once
    * bound. Right after a respawn this still names the old process's port,
    * which then refuses connections.
    */
  final class Watch private[ElectronSupport] (
      val process: Process,
      port: AtomicReference[Option[Int]]
  ) {
    def debugPort: Option[Int] = port.get
  }

  private val devToolsListening =
    """DevTools listening on ws://[^/\s]+:(\d+)/""".r.unanchored

  /** Runs `~esbuildServe` for an electron project (which must have had
    * [[writeRemoteDebug]] applied), ready once Electron's CDP endpoint reports
    * a page target.
    */
  def startWatch(directory: Path): Watch = {
    val port = new AtomicReference[Option[Int]](None)
    val process = E2ESupport.spawnWatch(
      directory,
      Map.empty,
      () => pageReady(port.get),
      300000,
      "Electron CDP endpoint",
      {
        case devToolsListening(bound) => port.set(Some(bound.toInt))
        case _                        => ()
      }
    )
    new Watch(process, port)
  }

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

  private def pageReady(debugPort: Option[Int]): Boolean =
    debugPort
      .flatMap(port => httpGet(s"http://localhost:$port/json", 2000))
      .exists(_.replaceAll("\\s", "").contains("\"type\":\"page\""))

  /** True once Electron's remote-debugging endpoint reports a `page` target. */
  def cdpPageReady(watch: Watch): Boolean = pageReady(watch.debugPort)

  /** The CDP endpoint's per-process browser id; changes when Electron respawns,
    * so a differing value confirms a main-process restart.
    */
  def cdpBrowserId(watch: Watch): Option[String] =
    watch.debugPort
      .flatMap(port => httpGet(s"http://localhost:$port/json/version", 2000))
      .flatMap { body =>
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

  /** Attaches a ChromeDriver to a copied project's already-running Electron via
    * its remote debugging port, using the Electron-matched chromedriver (not
    * Selenium Manager, whose Chrome would not match Electron's bundled
    * Chromium). The driver binary is bound to this service rather than the
    * global `webdriver.chrome.driver` property so it can't leak into the web
    * spec's Selenium-Manager driver.
    */
  def newDriver(chromedriver: Path, watch: Watch): WebDriver = {
    val port = watch.debugPort.getOrElse(
      sys.error("No Electron remote-debugging port reported yet")
    )
    val service = new ChromeDriverService.Builder()
      .usingDriverExecutable(chromedriver.toFile)
      .build()
    val options = new ChromeOptions
    options.addArguments("--remote-allow-origins=*")
    options.setExperimentalOption("debuggerAddress", s"localhost:$port")
    new ChromeDriver(service, options)
  }
}
