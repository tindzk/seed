package seed

import seed.cli.util.Ansi._
import seed.cli.util.Colour
import seed.cli.util.ColourScheme._

class Log(
  val f: String => Unit,
  val map: String => String,
  val level: LogLevel,
  val unicode: Boolean
) {
  import Log._
  import LogLevel._

  val DetailPrefix = " " * (if (unicode) UnicodeLength else NonUnicodeLength)

  def filter(f: String => Boolean): Log =
    new Log(m => if (f(m)) this.f(m), map, level, unicode)

  def prefix(text: String): Log = new Log(f, text + _, level, unicode)

  def debug(message: String, detail: Boolean = false): Unit =
    if (level <= Debug)
      f(
        foreground(green2)(
          (if (detail) DetailPrefix
           else bold(if (unicode) "↪ " else "[debug] ")) +
            map(message)
        )
      )

  def info(
    message: String,
    detail: Boolean = false,
    colour: Colour = blue2
  ): Unit =
    if (level <= Info)
      f(
        foreground(colour)(
          (if (detail) DetailPrefix
           else bold(if (unicode) "ⓘ " else " [info] ")) +
            map(message)
        )
      )

  def infoHighlight(message: String): Unit = info(message, detail = true, blue3)

  def infoRetainColour(message: String): Unit =
    if (level <= Info)
      f(
        foreground(blue2)(bold(if (unicode) "ⓘ " else " [info] ")) +
          map(message)
      )

  def warn(message: String, detail: Boolean = false): Unit =
    if (level <= Warn)
      f(
        foreground(yellow2)(
          (if (detail) DetailPrefix
           else bold(if (unicode) "⚠ " else " [warn] ")) +
            map(message)
        )
      )

  def error(message: String, detail: Boolean = false): Unit =
    if (level <= Error)
      f(
        foreground(red2)(
          (if (detail) DetailPrefix
           else bold(if (unicode) "✗ " else "[error] ")) +
            map(message)
        )
      )

  def newLine(): Unit = f(" ")
}

sealed abstract class LogLevel(val index: Int) extends Ordered[LogLevel] {
  def compare(that: LogLevel): Int = index.compare(that.index)
}

object LogLevel {
  case object Debug  extends LogLevel(0)
  case object Info   extends LogLevel(1)
  case object Warn   extends LogLevel(2)
  case object Error  extends LogLevel(3)
  case object Silent extends LogLevel(4)

  val All = Map(
    "debug"  -> Debug,
    "info"   -> Info,
    "warn"   -> Warn,
    "error"  -> Error,
    "silent" -> Silent
  )
}

object Log {
  val UnicodeLength    = 2
  val NonUnicodeLength = 8

  def apply(seedConfig: seed.model.Config): Log =
    new Log(println, identity, seedConfig.cli.level, seedConfig.cli.unicode)

  /** For test cases, only use for debugging purposes */
  def debug: Log = new Log(println, identity, LogLevel.Debug, false)

  /** For test cases, only use when errors should be silenced */
  def silent: Log = new Log(println, identity, LogLevel.Silent, false)

  /** For test cases, only report errors */
  def urgent: Log = new Log(println, identity, LogLevel.Error, false)
}
