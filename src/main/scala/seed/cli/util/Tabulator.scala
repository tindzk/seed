package seed.cli.util

// From https://stackoverflow.com/questions/7539831/scala-draw-table-to-console
object Tabulator {
  def format(table: Seq[Seq[fansi.Str]]): String = table match {
    case Seq() => ""
    case _ =>
      val sizes =
        for (row <- table)
          yield for (cell <- row)
            yield
              if (cell == null) 0
              else cell.length
      val colSizes = for (col <- sizes.transpose) yield col.max
      val rows     = for (row <- table) yield formatRow(row, colSizes)
      formatRows(rowSeparator(colSizes), rows)
  }

  def formatRows(rowSeparator: Position => String, rows: Seq[String]): String =
    (rowSeparator(First) ::
      rows.head ::
      rowSeparator(Other) ::
      rows.tail.toList :::
      rowSeparator(Last) ::
      List()).mkString("\n")

  def formatRow(row: Seq[fansi.Str], colSizes: Seq[Int]): String = {
    val cells =
      for ((item, size) <- row.zip(colSizes))
        yield
          if (size == 0) ""
          else item.toString() + " " * (size - item.length)
    cells.mkString("│ ", " │ ", " │")
  }

  sealed trait Position
  case object First extends Position
  case object Last  extends Position
  case object Other extends Position

  // See https://codegolf.stackexchange.com/questions/80634
  def rowSeparator(colSizes: Seq[Int])(position: Position): String =
    colSizes
      .map(size => "─" * (size + 2))
      .mkString(
        if (position == First) "╭"
        else if (position == Other) "├"
        else "╰",
        if (position == First) "┬"
        else if (position == Other) "┼"
        else "┴",
        if (position == First) "╮"
        else if (position == Other) "┤"
        else "╯"
      )
}
