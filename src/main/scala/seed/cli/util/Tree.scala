package seed.cli.util

/** Format two-level tree structures */
object Tree {
  def format(lines: Seq[Seq[fansi.Str]]): String = lines match {
    case Seq() => ""
    case _ =>
      val sizes =
        for (row <- lines)
          yield for (cell <- row) yield cell.length
      val colSizes = for (col <- sizes.transpose) yield col.max
      val rows = lines.zipWithIndex.map {
        case (row, i) =>
          formatRow(row, colSizes, i == lines.length - 1)
      }

      ("│" :: rows.toList).mkString("\n")
  }

  def formatRow(
    row: Seq[fansi.Str],
    colSizes: Seq[Int],
    last: Boolean
  ): String = {
    val cells =
      for ((item, size) <- row.zip(colSizes))
        yield
          if (size == 0) "" else item.toString() + " " * (size - item.length)

    cells.mkString(if (!last) "├─ " else "╰─ ", " ─ ", "")
  }
}
