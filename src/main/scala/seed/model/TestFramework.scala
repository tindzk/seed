package seed.model

sealed abstract class TestFramework(val artefact: Artefact, val mainClass: String)

object TestFramework {
  case object Minitest extends TestFramework(Artefact.Minitest, "minitest.runner.Framework")
  case object ScalaTest extends TestFramework(Artefact.ScalaTest, "org.scalatest.tools.Framework")
  case object ScalaCheck extends TestFramework(Artefact.ScalaCheck, "org.scalacheck.ScalaCheckFramework")
  case object Utest extends TestFramework(Artefact.Utest, "utest.runner.Framework")
}
