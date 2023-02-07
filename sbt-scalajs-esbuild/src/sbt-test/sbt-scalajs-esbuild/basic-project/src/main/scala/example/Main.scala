package example

import example.facade.Lodash

object Main {
  def main(args: Array[String]): Unit = {
    println(test())
  }

  def test(): String = {
    Lodash.toUpper("basic-project works!")
  }
}
