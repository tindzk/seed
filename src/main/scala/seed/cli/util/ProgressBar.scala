package seed.cli.util

/**
  * Ported from https://github.com/alexcrichton/cargo-fancy/blob/master/src/main.rs
  */
object ProgressBar {
  val icons = List("⡆", "⠇", "⠋", "⠙", "⠸", "⢰", "⣠", "⣄")

  sealed trait Result
  object Result {
    case object Waiting    extends Result
    case object InProgress extends Result
    case object Success    extends Result
    case object Warnings   extends Result
    case object Failure    extends Result
  }

  case class Line(
    tick: Int,
    result: Result,
    name: String,
    step: Int,
    total: Int
  )

  val pbPartChars = Array("░", "▏", "▎", "▍", "▌", "▋", "▊", "▉")

  // From https://mike42.me/blog/2018-06-make-better-cli-progress-bars-with-unicode-block-characters
  def progressBarString(progress: Double, width: Int): String = {
    val wholeWidth     = (progress * width).toInt
    val remainderWidth = (progress * width) % 1
    val partWidth      = (remainderWidth * pbPartChars.length.toDouble).toInt
    val partChar =
      if (width - wholeWidth - 1 < 0) "" else pbPartChars(partWidth)
    "█" * wholeWidth + partChar + "░" * (width - wholeWidth - 1)
  }

  def render(
    sb: StringBuilder,
    line: Line,
    nameLength: Int,
    width: Int
  ): Unit = {
    line.result match {
      case Result.InProgress =>
        val icon = icons(line.tick % icons.length)
        sb.append(ColourScheme.blue1.toFansi(s" [$icon] "))
      case Result.Success =>
        sb.append(ColourScheme.green1.toFansi(" [✓] "))
      case Result.Warnings =>
        sb.append(ColourScheme.yellow2.toFansi(" [⚠] "))
      case Result.Failure =>
        sb.append(ColourScheme.red1.toFansi(" [✗] "))
      case Result.Waiting =>
        sb.append(ColourScheme.yellow1.toFansi(" [⠿] "))
    }

    sb.append(line.name + (" " * (nameLength - line.name.length)) + " [")

    val remaining = width - (3 + 2 + nameLength + 3)

    val progress = if (line.total == 0) 0 else line.step.toDouble / line.total
    sb.append(progressBarString(progress, remaining))

    sb.append("]")
  }

  def printAll(progress: collection.Seq[(String, Line)]): String = {
    val sb         = new StringBuilder
    val nameLength = progress.map(_._2.name.length).max

    progress.foreach {
      case (_, line) =>
        render(sb, line, nameLength, 80)
        sb.append("\n")
    }

    sb.toString
  }
}
