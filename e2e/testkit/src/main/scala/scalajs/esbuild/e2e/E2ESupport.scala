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

import scala.jdk.CollectionConverters._

import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver

// Plugin-agnostic machinery shared by the per-module hot-reload e2e specs: copy
// an example to a throwaway dir, run the real `sbt ~esbuildServe` against it,
// drive a browser, and edit sources. Web- and electron-specific helpers (driver
// creation, example set, watch readiness, remote-debug wiring) live in
// `WebSupport` / `ElectronSupport` in their respective modules.
object E2ESupport {

  /** A required `-D` system property (set via the module's
    * `Test / javaOptions`).
    */
  def prop(name: String): String =
    sys.props.getOrElse(name, sys.error(s"System property [$name] not set"))

  def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  def isMac: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")

  /** Copies example `name` from `examplesRoot` into a fresh temp dir (excluding
    * `target` and `node_modules`) and, on the `snapshot` channel, repoints the
    * copy at the locally-published plugin version. The original is never
    * touched.
    */
  def copyExample(examplesRoot: String, name: String): Path = {
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
    * blocks until `ready` holds or the process dies / the timeout elapses. Each
    * module supplies its own readiness check.
    */
  def spawnWatch(
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

  /** True once a TCP connection to `port` on localhost succeeds. */
  def tcpReachable(port: Int): Boolean = {
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
