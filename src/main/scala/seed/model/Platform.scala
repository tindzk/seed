package seed.model

sealed abstract class Platform(val id: String, val caption: String)

object Platform {
  case object JVM extends Platform("jvm", "JVM")
  case object JavaScript extends Platform("js", "JavaScript")
  case object Native extends Platform("native", "Native")

  val All: Map[Platform, Int] = Map(JVM -> 0, JavaScript -> 1, Native -> 2)

  val Ordering = new Ordering[Platform] {
    override def compare(x: Platform, y: Platform): Int =
      All(x).compare(All(y))
  }
}

