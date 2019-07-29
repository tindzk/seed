package seed.cli.util

case class Colour(r: Int, g: Int, b: Int) {
  def toFansi = fansi.Color.True(r, g, b)
}

object Colour {
  def parseHex(str: String): Colour = {
    val r = Integer.valueOf(str.substring(1, 3), 16)
    val g = Integer.valueOf(str.substring(3, 5), 16)
    val b = Integer.valueOf(str.substring(5, 7), 16)

    Colour(r, g, b)
  }
}

// Based on w0ng's Hybrid theme
// https://gist.github.com/w0ng/3278077
object ColourScheme {
  val fg = Colour.parseHex("#C5C8C6")

  val red1 = Colour.parseHex("#A54242")
  val red2 = Colour.parseHex("#CC6666")

  val green1 = Colour.parseHex("#8C9440")
  val green2 = Colour.parseHex("#B5BD68")

  val yellow1 = Colour.parseHex("#DE935F")
  val yellow2 = Colour.parseHex("#F0C674")

  val blue1 = Colour.parseHex("#5F819D")
  val blue2 = Colour.parseHex("#81A2BE")
  val blue3 = Colour.parseHex("#92aec7")
}

object Ansi {
  def esc(str: String*)  = "\u001b[" + str.mkString(";")
  def escM(str: String*) = "\u001b[" + str.mkString(";") + "m"

  def foreground(colour: Colour)(text: String): String =
    escM("38", "2", colour.r.toString, colour.g.toString, colour.b.toString) + text + escM(
      "39"
    )

  def background(colour: Colour)(text: String): String =
    escM("48", "2", colour.r.toString, colour.g.toString, colour.b.toString) + text + escM(
      "49"
    )

  def bold(text: String): String       = escM("1") + text + escM("22")
  def italic(text: String): String     = escM("3") + text + escM("23")
  def underlined(text: String): String = escM("4") + text + escM("24")
}
