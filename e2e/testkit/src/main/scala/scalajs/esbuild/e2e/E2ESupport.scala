package scalajs.esbuild.e2e

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher

import scala.jdk.CollectionConverters._

import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions

// Shared machinery for hot-reload e2e specs: copy an example to a throwaway
// dir, run the real `sbt ~esbuildServe` against it, and drive a browser.
object E2ESupport {

  // Pinned so Selenium Manager fetches a deterministic browser+driver pair both
  // locally and in CI, instead of resolving against whatever is on PATH.
  private val chromeForTestingVersion = "151.0.7922.47"
  private val firefoxVersion = "153.0"

  private def prop(name: String): String =
    sys.props.getOrElse(name, sys.error(s"System property [$name] not set"))

  private def isWindows =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Copies a web example, ready to run `~esbuildServe` against. */
  def copyExample(name: String): Path =
    copyExampleFrom(prop("examples.web"), name)

  /** Copies an electron example and adds a matching `electron-chromedriver` so
    * Selenium can attach to the Electron the plugin spawns.
    */
  def copyElectronExample(name: String): Path = {
    val target = copyExampleFrom(prop("examples.electron"), name)
    addElectronChromedriver(target)
    target
  }

  /** Copies example `name` from `examplesRoot` into a fresh temp dir (excluding
    * `target` and `node_modules`) and, on the `snapshot` channel, repoints the
    * copy at the locally-published plugin version. The original is never
    * touched.
    */
  private def copyExampleFrom(examplesRoot: String, name: String): Path = {
    val source = Paths.get(examplesRoot, name)
    if (!Files.isDirectory(source))
      sys.error(s"Example [$name] not found at [$source]")
    val target = Files.createTempDirectory(s"e2e-$name-")

    val paths = Files.walk(source)
    try {
      paths.forEach { path =>
        val relative = source.relativize(path)
        val ignored = (0 until relative.getNameCount).exists { i =>
          val segment = relative.getName(i).toString
          segment == "target" || segment == "node_modules"
        }
        if (!ignored) {
          val destination = target.resolve(relative.toString)
          if (Files.isDirectory(path)) Files.createDirectories(destination)
          else {
            Files.createDirectories(destination.getParent)
            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
          }
        }
      }
    } finally paths.close()

    if (prop("plugin.channel") == "snapshot") {
      val pluginsSbt = target.resolve("project/plugins.sbt")
      val original =
        new String(Files.readAllBytes(pluginsSbt), StandardCharsets.UTF_8)
      val rewritten = original.replaceAll(
        """("me\.ptrdom"\s*%\s*"[^"]+"\s*%\s*")[^"]+(")""",
        "$1" + Matcher.quoteReplacement(prop("plugin.version")) + "$2"
      )
      if (rewritten == original)
        sys.error(
          s"Could not rewrite plugin version in [$pluginsSbt]:\n$original"
        )
      Files.write(pluginsSbt, rewritten.getBytes(StandardCharsets.UTF_8))
    }

    target
  }

  /** Adds `electron-chromedriver`, pinned to the example's Electron version, to
    * the copy's esbuild package. Its postinstall provides a `chromedriver` that
    * matches Electron's bundled Chromium (Selenium Manager can't supply that).
    */
  private def addElectronChromedriver(projectDir: Path): Unit = {
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

  /** Reserves a currently-free TCP port by binding and releasing it. A small
    * race exists between release and the child re-binding it, tolerable for a
    * single sequential spec (and far safer than a fixed port that a leftover
    * server could already hold).
    */
  def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  /** Pins the dev server's proxy port (the one the browser connects to) for a
    * copied project by appending an sbt setting, so each spec gets its own port
    * instead of the plugin default. The key must be `Compile`-scoped: the
    * plugin installs it via `inConfig(Compile)`, so an unscoped override is
    * silently ignored. `esbuildServe`/`serverPort` are in scope via the web
    * plugin's autoImport.
    */
  def writeServerPort(projectDir: Path, port: Int): Unit =
    Files.write(
      projectDir.resolve("e2e-server-port.sbt"),
      s"Compile / esbuildServe / serverPort := $port\n"
        .getBytes(StandardCharsets.UTF_8)
    )

  def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val paths = Files.walk(path)
      try {
        paths
          .sorted(Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
      } finally paths.close()
    }
  }

  /** Spawns `sbt ~esbuildServe` (with `extraEnv` added to its environment) and
    * blocks until `ready` holds or the process dies / the timeout elapses.
    */
  private def spawnWatch(
      directory: Path,
      extraEnv: Map[String, String],
      ready: () => Boolean,
      timeoutMillis: Long,
      describe: String
  ): Process = {
    val launcher = if (isWindows) List("cmd", "/c", "sbt") else List("sbt")
    val command = new java.util.ArrayList[String]()
    (launcher :+ "~esbuildServe").foreach(command.add)
    val builder = new ProcessBuilder(command)
    builder.directory(directory.toFile)
    extraEnv.foreach { case (key, value) =>
      builder.environment().put(key, value)
    }
    // stdin stays an open, idle pipe: an EOF makes `~` quit immediately.
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    val process = builder.start()

    val deadline = System.currentTimeMillis() + timeoutMillis
    var up = false
    while (!up && System.currentTimeMillis() < deadline) {
      if (!process.isAlive) {
        sys.error(
          s"`sbt ~esbuildServe` exited early with code [${process.exitValue()}]"
        )
      }
      if (ready()) up = true else Thread.sleep(1000)
    }
    if (!up) {
      stopWatch(process)
      sys.error(s"$describe did not become ready within timeout")
    }
    process
  }

  /** Runs `~esbuildServe` for a web project, ready once the dev server on
    * `port` accepts connections.
    */
  def startWatch(directory: Path, port: Int): Process =
    spawnWatch(
      directory,
      Map.empty,
      () => tcpReachable(port),
      240000,
      s"Dev server on port [$port]"
    )

  /** Runs `~esbuildServe` for an electron project (which must have had
    * [[writeElectronRemoteDebug]] applied), ready once Electron's CDP endpoint
    * reports a page target.
    */
  def startElectronWatch(directory: Path, debugPort: Int): Process =
    spawnWatch(
      directory,
      Map.empty,
      () => cdpPageReady(debugPort),
      300000,
      s"Electron CDP endpoint on port [$debugPort]"
    )

  /** Makes the Electron the plugin spawns expose a remote-debugging port for
    * Selenium to attach to, by transforming the copied project's generated
    * `esbuildServeScript` to add the switches to its `spawn(electron, ...)`
    * call. Kept out of the plugin: it's a test concern. The `Compile /
    * fastLinkJS` scope matches how the electron plugin defines the script for
    * the default FastOpt stage; `esbuildServeScript` is in scope via the web
    * plugin's autoImport.
    */
  def writeElectronRemoteDebug(projectDir: Path, debugPort: Int): Unit = {
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

  private def tcpReachable(port: Int): Boolean = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress("localhost", port), 1000)
      true
    } catch {
      case _: Throwable => false
    } finally {
      try socket.close()
      catch { case _: Throwable => () }
    }
  }

  def stopWatch(process: Process): Unit = {
    val descendants = new java.util.ArrayList[ProcessHandle]()
    process.descendants().forEach(descendants.add(_))
    process.destroy()
    descendants.forEach(handle => if (handle.isAlive) handle.destroy())
    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    descendants.forEach(handle => if (handle.isAlive) handle.destroyForcibly())
  }

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

  def js(driver: WebDriver): JavascriptExecutor =
    driver.asInstanceOf[JavascriptExecutor]

  def text(driver: WebDriver, selector: String): AnyRef =
    js(driver).executeScript(
      "const e = document.querySelector(arguments[0]); return e && e.textContent",
      selector
    )

  /** Computed style value with whitespace stripped (e.g. `rgb(0,191,255)`). */
  def css(driver: WebDriver, selector: String, property: String): String = {
    val value = js(driver).executeScript(
      "return getComputedStyle(document.querySelector(arguments[0]))[arguments[1]]",
      selector,
      property
    )
    Option(value).map(_.toString.replaceAll("\\s", "")).orNull
  }

  /** Text content of every element matching `selector`. */
  def texts(driver: WebDriver, selector: String): Seq[String] =
    js(driver)
      .executeScript(
        "return Array.from(document.querySelectorAll(arguments[0])).map(e => e.textContent)",
        selector
      )
      .asInstanceOf[java.util.List[String]]
      .asScala
      .toList

  /** `content` of a pseudo-element (e.g. `::after`), quotes included. */
  def pseudoContent(
      driver: WebDriver,
      selector: String,
      pseudo: String
  ): String = {
    val value = js(driver).executeScript(
      "return getComputedStyle(document.querySelector(arguments[0]), arguments[1]).content",
      selector,
      pseudo
    )
    Option(value).map(_.toString).orNull
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
  def electronChromedriver(projectDir: Path): Path = {
    val suffix = if (isWindows) ".exe" else ""
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
  def newElectronDriver(chromedriver: Path, debugPort: Int): WebDriver = {
    val service = new ChromeDriverService.Builder()
      .usingDriverExecutable(chromedriver.toFile)
      .build()
    val options = new ChromeOptions
    options.addArguments("--remote-allow-origins=*")
    options.setExperimentalOption("debuggerAddress", s"localhost:$debugPort")
    new ChromeDriver(service, options)
  }

  /** Targeted edit of a copied source file; fails if `from` is not present. */
  def edit(path: Path, from: String, to: String): Unit = {
    val original = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    if (!original.contains(from))
      sys.error(s"Expected to find [$from] in [$path]")
    Files.write(
      path,
      original.replace(from, to).getBytes(StandardCharsets.UTF_8)
    )
  }
}
