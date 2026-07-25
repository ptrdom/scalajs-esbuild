package scalajs.esbuild.e2e

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher

import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions

// Shared machinery for hot-reload e2e specs: copy an example to a throwaway
// dir, run the real `sbt ~esbuildServe` against it, and drive a browser.
object E2ESupport {

  // Pinned so Selenium Manager fetches a deterministic browser+driver pair both
  // locally and in CI, instead of resolving against whatever is on PATH.
  private val chromeForTestingVersion = "151.0.7922.47"
  private val firefoxVersion = "152.0"

  private def prop(name: String): String =
    sys.props.getOrElse(name, sys.error(s"System property [$name] not set"))

  private def isWindows =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Copies example `name` into a fresh temp dir (excluding `target` and
    * `node_modules`) and, on the `snapshot` channel, repoints the copy at the
    * locally-published plugin version. The original example is never touched.
    */
  def copyExample(name: String): Path = {
    val source = Paths.get(prop("examples.web"), name)
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

  /** Spawns `sbt ~esbuildServe` and blocks until the dev server accepts
    * connections on `port`.
    */
  def startWatch(directory: Path, port: Int): Process = {
    val launcher = if (isWindows) List("cmd", "/c", "sbt") else List("sbt")
    val command = new java.util.ArrayList[String]()
    (launcher :+ "~esbuildServe").foreach(command.add)
    val builder = new ProcessBuilder(command)
    builder.directory(directory.toFile)
    // stdin stays an open, idle pipe: an EOF makes `~` quit immediately.
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    val process = builder.start()

    val deadline = System.currentTimeMillis() + 240000
    var connected = false
    while (!connected && System.currentTimeMillis() < deadline) {
      if (!process.isAlive) {
        sys.error(
          s"`sbt ~esbuildServe` exited early with code [${process.exitValue()}]"
        )
      }
      val socket = new Socket()
      try {
        socket.connect(new InetSocketAddress("localhost", port), 1000)
        connected = true
      } catch {
        case _: Throwable => Thread.sleep(1000)
      } finally {
        try socket.close()
        catch { case _: Throwable => () }
      }
    }
    if (!connected) {
      stopWatch(process)
      sys.error(s"Dev server did not start on port [$port] within timeout")
    }
    process
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
