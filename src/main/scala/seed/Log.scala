package seed

import seed.cli.util.Ansi._
import seed.cli.util.ColourScheme._

class Log(f: String => Unit, map: String => String = identity) {
  def prefix(text: String): Log = new Log(f, text + _)

  def error(message: String): Unit =
    f(foreground(red2)(bold("[error]") + " " + map(message)))

  def warn(message: String): Unit =
    f(foreground(yellow2)(bold("[warn]") + "  " + map(message)))

  def debug(message: String): Unit =
    f(foreground(green2)(bold("[debug]") + " " + map(message)))

  def info(message: String): Unit =
    f(foreground(blue2)(bold("[info]") + "  " + map(message)))
}

object Log extends Log(println, identity)
