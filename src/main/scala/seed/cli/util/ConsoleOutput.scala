package seed.cli.util

import seed.Log

class ConsoleOutput(parentLog: Log, print: String => Unit) {
  val log = new Log(
    l => write(l + "\n", sticky = false),
    identity,
    parentLog.level,
    parentLog.unicode
  )

  private var stickyLines = 0
  private var clearLines  = 0
  private var flushed     = false

  def isFlushed: Boolean = flushed

  def processLines(output: String): String =
    output.flatMap {
      case '\n' if clearLines != 0 =>
        clearLines -= 1

        // Clear until end of line
        Ansi.Escape + "0K" + "\n"

      case c => c.toString
    }

  def write(output: String, sticky: Boolean = false): Unit = {
    require(output.endsWith("\n"))

    if (sticky) {
      require(!flushed)
      val lines = output.count(_ == '\n')
      require(stickyLines == 0 || stickyLines == lines)

      if (stickyLines == 0) {
        stickyLines = lines
        print(output)
      } else {
        // Move up
        print(Ansi.Escape + s"${stickyLines}A" + processLines(output))
      }
    } else {
      if (stickyLines > 0) {
        clearLines = stickyLines
        print(Ansi.Escape + s"${stickyLines}A" + processLines(output))
        stickyLines = 0
      } else {
        print(processLines(output))
      }
    }
  }

  def flushSticky(): Unit = {
    stickyLines = 0
    clearLines = 0
    flushed = true
  }

  def reset(): Unit = {
    stickyLines = 0
    clearLines = 0
    flushed = false
  }
}
