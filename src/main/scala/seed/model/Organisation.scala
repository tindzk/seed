package seed.model

sealed abstract class Organisation(val packageName: String, val caption: String)

object Organisation {
  case object Lightbend extends Organisation("org.scala-lang", "Lightbend")
  case object Typelevel extends Organisation("org.typelevel", "Typelevel")

  def resolve(organisationName: String): Option[Organisation] =
    if (Lightbend.packageName == organisationName) Some(Lightbend)
    else if (Typelevel.packageName == organisationName) Some(Typelevel)
    else None
}
