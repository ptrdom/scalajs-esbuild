import java.io.File
import java.nio.file.Files

import sbt.*
import sbt.AutoPlugin
import sbt.Keys.*

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

object ExampleVersionPlugin extends AutoPlugin {

  object autoImport {
    val exampleVersionCheck =
      taskKey[Unit]("Checks if every example contains a valid plugin version")
  }

  import autoImport.*

  private val regex =
    "[\\\"](.+)[\\\"]\\s*[%]\\s*[\\\"](.+)[\\\"]\\s*[%]\\s*[\\\"](.+)[\\\"]".r

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    exampleVersionCheck := {
      val log = streams.value.log

      val isSnapshotV = isSnapshot.value
      val versionV = version.value
      val examplesDirectory = (baseDirectory.value / "examples")
      val organizationV = organization.value
      val nameV = name.value

      if (isSnapshotV) {
        log.debug(s"Snapshot version, skipping example version check")
      } else {
        val invalidExamples = Using(
          Files.list(examplesDirectory.toPath)
        )(
          _.iterator().asScala
            .map(_.toFile)
            .filter(_.isDirectory)
            .flatMap { example =>
              val exampleVersionValid = Using(Files.walk(example.toPath))(
                _.iterator().asScala
                  .map(_.toFile)
                  .filter(file => file.isFile && file.getName.endsWith(".sbt"))
                  .exists { sbtFile =>
                    Using(Source.fromFile(sbtFile)) { source =>
                      source
                        .getLines()
                        .exists { line =>
                          regex
                            .findFirstMatchIn(line)
                            .exists(m =>
                              m.group(1) == organizationV && m
                                .group(2) == nameV && m.group(3) == versionV
                            )
                        }
                    }.getOrElse(sys.error(s"Failed to read file [$sbtFile]"))
                  }
              )
                .fold(
                  ex =>
                    throw new RuntimeException(
                      s"Failed to walk example directory [$example]",
                      ex
                    ),
                  identity
                )
              if (exampleVersionValid) {
                None
              } else {
                Some(example)
              }
            }
            .toList
        ).fold(
          ex =>
            throw new RuntimeException(
              s"Failed to list examples directory [$examplesDirectory]",
              ex
            ),
          identity
        )
        if (invalidExamples.nonEmpty) {
          throw new RuntimeException(
            s"Not all examples contained a valid plugin version [$versionV] - " +
              s"[${invalidExamples.mkString(File.pathSeparator)}]"
          )
        }
        log.debug(s"All examples valid")
      }
    }
  )
}
