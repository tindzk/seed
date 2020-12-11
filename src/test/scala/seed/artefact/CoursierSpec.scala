package seed.artefact

import java.nio.file.Path

import minitest.SimpleTestSuite
import seed.Log
import seed.model.{Artefact, Build, Platform}
import seed.model.Build.JavaDep

object CoursierSpec extends SimpleTestSuite {
  var resolution: Coursier.ResolutionResult = _

  val scalaOrganisation = "org.scala-lang"
  val scalaVersion      = "2.12.8"

  test("Resolve dependency") {
    val dep = JavaDep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6")
    resolution = Coursier.resolveAndDownload(
      Set(dep),
      (scalaOrganisation, scalaVersion),
      Build.Resolvers(),
      Coursier.DefaultIvyPath,
      Coursier.DefaultCachePath,
      optionalArtefacts = true,
      silent = true,
      Log.urgent
    )

    val result =
      Coursier.localArtefacts(resolution, Set(dep), optionalArtefacts = true)
    assert(
      result.exists(_.javaDocJar.nonEmpty) &&
        result.exists(_.sourcesJar.nonEmpty)
    )
  }

  test("Get artefact path") {
    val artefact          = Artefact("org.scala-js", "scalajs-dom_sjs0.6_2.12", None)
    val version           = "0.9.6"
    val unresolvedVersion = "0.0.1"

    val path = Coursier.artefactPath(
      resolution,
      artefact,
      Platform.JavaScript,
      "0.6.26",
      "2.12.8",
      version
    )
    assert(
      path.exists(_.toString.endsWith("scalajs-dom_sjs0.6_2.12-0.9.6.jar"))
    )

    val path2 = Coursier.artefactPath(
      resolution,
      artefact,
      Platform.JavaScript,
      "0.6.26",
      "2.12.8",
      unresolvedVersion
    )
    assert(path2.isEmpty)
  }

  test("Resolve dependency for Typelevel Scala") {
    val dep = JavaDep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6")
    val resolution = Coursier.resolveAndDownload(
      Set(dep),
      ("org.typelevel", "2.12.4-bin-typelevel-4"),
      Build.Resolvers(),
      Coursier.DefaultIvyPath,
      Coursier.DefaultCachePath,
      optionalArtefacts = true,
      silent = true,
      Log.urgent
    )

    def artefact(path: Path): String = path.getFileName.toString

    val result =
      Coursier.localArtefacts(resolution, Set(dep), optionalArtefacts = true)
    assertEquals(
      result.map(_.libraryJar).map(artefact),
      List(
        "scalajs-library_2.12-0.6.23.jar",
        "scala-library-2.12.4-bin-typelevel-4.jar",
        "scalajs-dom_sjs0.6_2.12-0.9.6.jar"
      )
    )
  }

  test("Resolve dependencies with runtime scope") {
    val dep = JavaDep("io.undertow", "undertow-core", "2.0.13.Final")
    val resolution = Coursier.resolveAndDownload(
      Set(dep),
      (scalaOrganisation, scalaVersion),
      Build.Resolvers(),
      Coursier.DefaultIvyPath,
      Coursier.DefaultCachePath,
      optionalArtefacts = true,
      silent = true,
      Log.urgent
    )

    val result = Coursier
      .resolveSubset(resolution.resolution, Set(dep), optionalArtefacts = true)
      .toList
      .map(_._1)
      .filter(_.organisation == "org.jboss.xnio")
      .map(_.artefact)
      .sorted

    // The xnio-nio dependency sets the runtime scope
    // See also https://repo1.maven.org/maven2/io/undertow/undertow-core/2.0.13.Final/undertow-core-2.0.13.Final.pom
    assertEquals(result, List("xnio-api", "xnio-nio"))
  }
}
