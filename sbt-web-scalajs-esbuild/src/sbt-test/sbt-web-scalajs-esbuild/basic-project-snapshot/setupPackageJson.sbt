InputKey[Unit]("setupPackageJson") := {
  import _root_.io.circe.parser._

  val ags = Def.spaceDelimited().parsed.toList

  sealed trait PackageManager
  object PackageManager {
    case object Pnpm extends PackageManager
    case object Yarn extends PackageManager
    case object Berry extends PackageManager
  }

  val packageManager =
    ags.headOption.map(_.toLowerCase) match {
      case Some("pnpm")  => PackageManager.Pnpm
      case Some("yarn")  => PackageManager.Yarn
      case Some("berry") => PackageManager.Berry
      case invalid => sys.error(s"invalid package manager argument [$invalid]")
    }

  def readAndParseFile(file: File) =
    parse(IO.readLines(file).mkString).toTry.get

  val packageManagerDir =
    baseDirectory.value / packageManager.toString.toLowerCase

  val esbuildDir = baseDirectory.value / "client" / "esbuild"

  val sourceJson = readAndParseFile(baseDirectory.value / "source-package.json")

  val packageManagerJson = readAndParseFile(packageManagerDir / "package.json")

  val mergedJson = sourceJson.deepMerge(packageManagerJson).spaces2

  IO.write(esbuildDir / "package.json", mergedJson.getBytes)

  packageManagerDir
    .listFiles()
    .filter(file => file.isFile && file.getName != "package.json")
    .foreach(file => IO.copyFile(file, esbuildDir / file.getName))
}
