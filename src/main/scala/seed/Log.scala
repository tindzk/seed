package seed

import seed.cli.util.Ansi._
import seed.cli.util.ColourScheme._

class Log(f: String => Unit) {
  def error(message: String): Unit =
    f(foreground(red2)(bold("[error]") + " " + message))

  def warn(message: String): Unit =
    f(foreground(yellow2)(bold("[warn]") + " " + message))

  def debug(message: String): Unit =
    f(foreground(green2)(bold("[debug]") + " " + message))

  def info(message: String): Unit =
    f(foreground(blue2)(bold("[info]") + " " + message))
}

object Log extends Log(println)