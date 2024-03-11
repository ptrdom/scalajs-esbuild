package scalajs.esbuild

import sbt.File
import sbt.util.Logger

import scala.sys.process.Process

trait PackageManager {

  def name: String
  def lockFile: String

  def installCommand: String
  def install(logger: Logger)(directory: File): Unit = {
    val exitValue = Process(
      (if (isWindows) "cmd" :: "/c" :: Nil
       else Nil) ::: name :: Nil ::: installCommand :: Nil,
      directory
    ).run(logger).exitValue()
    if (exitValue != 0) {
      sys.error(s"Nonzero exit value: $exitValue")
    } else ()
  }
}

object PackageManager {
  object Npm extends PackageManager {
    override def name: String = "npm"
    override def lockFile: String = "package-lock.json"
    override def installCommand: String = "install"
  }
}
