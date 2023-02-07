package scalajsesbuild

import sbt._
import sbt.util.Logger
import scalajsesbuild.Logging.eagerLogger

import scala.sys.process.Process

trait EsbuildRunner {
  def process(logger: Logger)(command: List[String], directory: File): Process
  def run(logger: Logger)(command: List[String], directory: File): Unit
}

object EsbuildRunner {
  object Default extends EsbuildRunner {
    override def process(
        logger: Logger
    )(command: List[String], directory: File): Process = {
      Process(prepareCommand(directory, command), directory)
        .run(eagerLogger(logger))
    }

    override def run(
        logger: Logger
    )(command: List[String], directory: File): Unit = {
      val fullCommand = prepareCommand(directory, command)
      logger.info(s"Running [$fullCommand]")
      val exitValue = Process(fullCommand, directory)
        .run(eagerLogger(logger))
        .exitValue()
      if (exitValue != 0) {
        sys.error(s"Nonzero exit value: $exitValue")
      } else ()
    }

    private val executable = List(
      Some("esbuild"),
      if (
        sys
          .props("os.name")
          .toLowerCase
          .contains("win")
      ) Some("cmd")
      else None
    ).flatten.mkString(".")

    private def prepareCommand(directory: File, command: List[String]) = {
      (directory / "node_modules" / ".bin" / executable).absolutePath :: command
    }
  }
}
