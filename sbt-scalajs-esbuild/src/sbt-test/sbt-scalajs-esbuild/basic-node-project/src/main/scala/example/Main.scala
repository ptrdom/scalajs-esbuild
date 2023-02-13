package example

import example.facade.Lodash

object Main {
  def main(args: Array[String]): Unit = {
    println(testString())
  }

  def testString(): String = {
    Lodash.toUpper("basic-node-project works!")
  }
}
