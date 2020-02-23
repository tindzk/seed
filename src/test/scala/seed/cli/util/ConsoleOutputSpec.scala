package seed.cli.util

import minitest.SimpleTestSuite
import seed.Log

import scala.util.Try

object ConsoleOutputSpec extends SimpleTestSuite {
  test("Write regular") {
    var output = ""
    val co     = new ConsoleOutput(Log.silent, output += _)
    co.writeRegular("a\n")
    assertEquals(output, "a\n")
    co.writeRegular("b\n")
    assertEquals(output, "a\nb\n")
  }

  test("Write sticky and update") {
    var output = ""
    val co     = new ConsoleOutput(Log.silent, output += _)
    co.writeSticky("abc\n")
    assertEquals(output, "abc\n")

    co.writeSticky("d\n")

    // Move up and clear until the end of the line
    val moveUp = s"${Ansi.Escape}1A"
    val clear  = s"${Ansi.Escape}0K"
    assertEquals(output, s"abc\n${moveUp}d$clear\n")

    // Number of lines must match (1 != 2)
    assert(Try(co.writeSticky("b\nc\n")).isFailure)

    output = ""
    co.writeSticky("ef\n")
    assertEquals(output, s"${moveUp}ef$clear\n")
  }

  test("Write regular after sticky") {
    var output = ""
    val co     = new ConsoleOutput(Log.silent, output += _)
    co.writeSticky("abc\ndef\n")
    co.writeRegular("g\n")

    val moveUp = s"${Ansi.Escape}2A"
    val clear  = s"${Ansi.Escape}0K"
    assertEquals(output, s"abc\ndef\n${moveUp}g$clear\nabc$clear\ndef\n")

    output = ""
    co.writeSticky("hi\njk\n")
    assertEquals(output, s"${moveUp}hi${clear}\njk${clear}\n")

    output = ""
    co.writeRegular("m\n")
    assertEquals(output, s"${moveUp}m${clear}\nhi$clear\njk\n")
  }
}
