package scalajsesbuild

import sbt._
import sbt.util.Logger

import scala.sys.process.Process

trait EsbuildRunner {
  def process(logger: Logger)(scriptFileName: String, directory: File): Process
  def run(logger: Logger)(scriptFileName: String, directory: File): Unit
}

object EsbuildRunner {
  object Default extends EsbuildRunner {
    override def process(
        logger: Logger
    )(scriptFileName: String, directory: File): Process = {
      Process(prepareCommand(scriptFileName), directory)
        .run(logger)
    }

    override def run(
        logger: Logger
    )(scriptFileName: String, directory: File): Unit = {
      val fullCommand = prepareCommand(scriptFileName)
      val exitValue = Process(fullCommand, directory)
        .run(logger)
        .exitValue()
      if (exitValue != 0) {
        sys.error(s"Nonzero exit value: $exitValue")
      } else ()
    }

    private def prepareCommand(scriptFileName: String) = {
      "node" :: scriptFileName :: Nil
    }
  }
}
