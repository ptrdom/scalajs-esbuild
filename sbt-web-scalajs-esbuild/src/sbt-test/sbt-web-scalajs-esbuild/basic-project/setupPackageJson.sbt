InputKey[Unit]("setupPackageJson") := {
  import _root_.io.circe.parser._

  val ags = Def.spaceDelimited().parsed.toList

  sealed trait PackageManager
  object PackageManager {
    case object Pnpm extends PackageManager
    case object Yarn extends PackageManager
  }

  val packageManager =
    ags.headOption.map(_.toLowerCase) match {
      case Some("pnpm") => PackageManager.Pnpm
      case Some("yarn") => PackageManager.Yarn
      case invalid => sys.error(s"invalid package manager argument [$invalid]")
    }

  def readAndParseFile(file: File) =
    parse(IO.readLines(file).mkString).toTry.get

  val sourceJson = readAndParseFile(baseDirectory.value / "package.json")

  val packageManagerJson = readAndParseFile(
    baseDirectory.value / packageManager.toString / "package.json"
  )

  val mergedJson = sourceJson.deepMerge(packageManagerJson).spaces2

  IO.write(
    baseDirectory.value / "client" / "esbuild" / "package.json",
    mergedJson.getBytes
  )
}
