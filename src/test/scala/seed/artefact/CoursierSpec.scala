package seed.artefact

import minitest.SimpleTestSuite
import seed.Log
import seed.model.{Artefact, Build, Platform}
import seed.model.Build.JavaDep

object CoursierSpec extends SimpleTestSuite {
  var resolution: Coursier.ResolutionResult = _

  test("Resolve dependency") {
    val dep = JavaDep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6")
    resolution = Coursier.resolveAndDownload(Set(dep),
      Build.Resolvers(), Coursier.DefaultIvyPath,
      Coursier.DefaultCachePath, optionalArtefacts = true, silent = true,
      Log.urgent)

    val result =
      Coursier.localArtefacts(resolution, Set(dep), optionalArtefacts = true)
    assert(
      result.exists(_.javaDocJar.nonEmpty) &&
      result.exists(_.sourcesJar.nonEmpty))
  }

  test("Get artefact path") {
    val artefact = Artefact("org.scala-js", "scalajs-dom_sjs0.6_2.12", None)
    val version = "0.9.6"
    val unresolvedVersion = "0.0.1"

    val path = Coursier.artefactPath(resolution, artefact, Platform.JavaScript,
      "0.6.26", "2.12.8", version)
    assert(path.exists(_.toString.endsWith("scalajs-dom_sjs0.6_2.12-0.9.6.jar")))

    val path2 = Coursier.artefactPath(resolution, artefact, Platform.JavaScript,
      "0.6.26", "2.12.8", unresolvedVersion)
    assert(path2.isEmpty)
  }
}
