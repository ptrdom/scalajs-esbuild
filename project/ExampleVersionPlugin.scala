import java.io.File
import java.nio.file.Files

import sbt.*
import sbt.AutoPlugin
import sbt.Keys.*
import sbt.complete.DefaultParsers.*

import sbtdynver.DynVerPlugin
import sbtdynver.DynVerPlugin.autoImport.previousStableVersion

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

object ExampleVersionPlugin extends AutoPlugin {

  override def requires: Plugins = DynVerPlugin

  object autoImport {
    val exampleVersionCheck =
      taskKey[Unit]("Checks if every example contains a valid plugin version")
    val exampleVersionWrite =
      inputKey[Unit]("Writes the given plugin version into every example")
  }

  import autoImport.*

  private val regex =
    "[\\\"](.+)[\\\"]\\s*[%]\\s*[\\\"](.+)[\\\"]\\s*[%]\\s*[\\\"](.+)[\\\"]".r

  // For a snapshot version the examples are expected to reference the latest
  // non-snapshot (stable) version, for a non-snapshot version they are expected
  // to reference the current version. Returns None when a snapshot version has
  // no previous stable version to fall back on.
  private def resolveExampleVersion(
      isSnapshotV: Boolean,
      versionV: String,
      previousStableVersionV: Option[String]
  ): Option[String] =
    if (isSnapshotV) previousStableVersionV else Some(versionV)

  private def listExamples(examplesDirectory: File): List[File] =
    Using(
      Files.list(examplesDirectory.toPath)
    )(
      _.iterator().asScala
        .map(_.toFile)
        .filter(_.isDirectory)
        .toList
    ).fold(
      ex =>
        throw new RuntimeException(
          s"Failed to list examples directory [$examplesDirectory]",
          ex
        ),
      identity
    )

  private def listSbtFiles(example: File): List[File] =
    Using(Files.walk(example.toPath))(
      _.iterator().asScala
        .map(_.toFile)
        .filter(file => file.isFile && file.getName.endsWith(".sbt"))
        .toList
    ).fold(
      ex =>
        throw new RuntimeException(
          s"Failed to walk example directory [$example]",
          ex
        ),
      identity
    )

  private def readLines(sbtFile: File): List[String] =
    Using(Source.fromFile(sbtFile))(_.getLines().toList)
      .getOrElse(sys.error(s"Failed to read file [$sbtFile]"))

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    exampleVersionCheck := {
      val log = streams.value.log

      val isSnapshotV = isSnapshot.value
      val versionV = version.value
      val previousStableVersionV = previousStableVersion.value
      val examplesDirectory = (baseDirectory.value / "examples")
      val organizationV = organization.value
      val nameV = name.value

      resolveExampleVersion(
        isSnapshotV,
        versionV,
        previousStableVersionV
      ) match {
        case None =>
          log.debug(
            "No previous stable version available, skipping example version check"
          )
        case Some(exampleVersionV) =>
          val invalidExamples =
            listExamples(examplesDirectory).filterNot { example =>
              listSbtFiles(example).exists { sbtFile =>
                readLines(sbtFile).exists { line =>
                  regex
                    .findFirstMatchIn(line)
                    .exists(m =>
                      m.group(1) == organizationV && m
                        .group(2) == nameV && m.group(3) == exampleVersionV
                    )
                }
              }
            }
          if (invalidExamples.nonEmpty) {
            throw new RuntimeException(
              s"Not all examples contained a valid plugin version [$exampleVersionV] - " +
                s"[${invalidExamples.mkString(File.pathSeparator)}]"
            )
          }
          log.debug(s"All examples valid")
      }
    },
    exampleVersionWrite := {
      val exampleVersionV = (Space ~> token(NotSpace, "<version>")).parsed

      val log = streams.value.log

      val examplesDirectory = (baseDirectory.value / "examples")
      val organizationV = organization.value
      val nameV = name.value

      listExamples(examplesDirectory).foreach { example =>
        listSbtFiles(example).foreach { sbtFile =>
          val lines = readLines(sbtFile)
          val newLines = lines.map { line =>
            regex.findFirstMatchIn(line) match {
              case Some(m)
                  if m.group(1) == organizationV && m.group(2) == nameV =>
                line.substring(0, m.start(3)) + exampleVersionV + line
                  .substring(m.end(3))
              case _ => line
            }
          }
          if (newLines != lines) {
            IO.writeLines(sbtFile, newLines)
            log.info(
              s"Wrote plugin version [$exampleVersionV] into [$sbtFile]"
            )
          }
        }
      }
    }
  )
}
