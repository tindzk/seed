package seed.artefact

import minitest.SimpleTestSuite
import seed.model.Build
import seed.model.Build.JavaDep

object CoursierSpec extends SimpleTestSuite {
  test("Resolve dependency") {
    val dep = JavaDep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6")
    val resolution = Coursier.resolveAndDownload(Set(dep),
      Build.Resolvers(), Coursier.DefaultIvyPath,
      Coursier.DefaultCachePath, optionalArtefacts = true)

    val result =
      Coursier.localArtefacts(resolution, Set(dep), optionalArtefacts = true)
    assert(
      result.exists(_.javaDocJar.nonEmpty) &&
      result.exists(_.sourcesJar.nonEmpty))
  }
}
