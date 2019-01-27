package seed.artefact

import minitest.SimpleTestSuite
import seed.model.Build.Dep
import seed.model.Platform.JavaScript

object ArtefactResolutionSpec extends SimpleTestSuite {
  test("dependencyFromDep()") {
    val scalaDep = Dep("org.scala-js", "scalajs-dom", "0.9.6")
    val javaDep = ArtefactResolution.dependencyFromDep(
      scalaDep, JavaScript, "0.6", "2.12")
    assertEquals(javaDep,
      Dep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6"))
  }
}
