package seed.cli.util

import seed.Log

class ConsoleOutput(parentLog: Log, print: String => Unit) {
  val log = new Log(
    l => writeRegular(l + "\n"),
    identity,
    parentLog.level,
    parentLog.unicode
  )

  private var stickyLines = List[String]()
  private var flushed     = false

  def isFlushed: Boolean = flushed

  def processLines(output: List[String], clearLines: Int): String = {
    var clear = clearLines
    output.map { line =>
      val clearEsc =
        if (clear == 0) ""
        else {
          clear -= 1

          // Clear until end of line
          Ansi.Escape + "0K"
        }

      line + clearEsc + "\n"
    }.mkString
  }

  def writeRegular(output: String): Unit = {
    require(output.endsWith("\n"))

    if (stickyLines.isEmpty) print(output)
    else {
      val lines = output.split('\n').toList
      print(
        Ansi.Escape + s"${stickyLines.length}A" +
          processLines(lines ++ stickyLines, stickyLines.length)
      )
    }
  }

  def writeSticky(output: String): Unit = {
    require(output.endsWith("\n"))
    require(!flushed)
    val lines = output.count(_ == '\n')
    require(stickyLines.isEmpty || stickyLines.length == lines)

    val initialStickyLines = stickyLines.isEmpty
    stickyLines = output.split('\n').toList

    if (initialStickyLines) print(output)
    else
      // Move up first, then replace lines
      print(Ansi.Escape + s"${lines}A" + processLines(stickyLines, lines))
  }

  def flushSticky(): Unit = {
    stickyLines = List()
    flushed = true
  }

  def reset(): Unit = {
    stickyLines = List()
    flushed = false
  }
}

object ConsoleOutput {
  def conditional(
    progress: Boolean,
    consoleOutput: ConsoleOutput
  ): ConsoleOutput =
    if (progress) consoleOutput else new ConsoleOutput(Log.silent, _ => ())
}
