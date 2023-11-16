package example

import example.facade.Fs
import example.facade.Lodash
import example.facade.Os
import example.facade.Path

object Main {
  def main(args: Array[String]): Unit = {
    println(test())
  }

  def test(): String = {
    val file = Path.join(Fs.mkdtempSync(s"${Os.tmpdir()}${Path.sep}"), "test")
    Fs.writeFileSync(file, Lodash.toUpper("basic-node-project works!"), "utf8")
    Fs.readFileSync(file, "utf8")
  }
}
