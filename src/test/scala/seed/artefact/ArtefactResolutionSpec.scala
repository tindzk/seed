package seed.artefact

import java.nio.file.Paths

import minitest.SimpleTestSuite
import seed.model.Build.Dep
import seed.model.Platform.JavaScript
import seed.model.Build.{Module, Project}
import seed.model.Platform.JVM
import seed.model.Build

object ArtefactResolutionSpec extends SimpleTestSuite {
  test("dependencyFromDep()") {
    val scalaDep = Dep("org.scala-js", "scalajs-dom", "0.9.6")
    val javaDep = ArtefactResolution.dependencyFromDep(
      scalaDep, JavaScript, "0.6", "2.12")
    assertEquals(javaDep,
      Dep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6"))
  }

  test("Extract platform dependencies of test module in libraryDeps()") {
    val build =
      Build(
        project = Project("2.12.8", scalaJsVersion = Some("0.6.26")),
        module = Map(
          "a" -> Module(
            targets = List(JVM, JavaScript),
            test = Some(Module(
              sources = List(Paths.get("a/test")),
              scalaDeps = List(Dep("io.monix", "minitest", "2.3.2")))))))

    val libraryDeps = ArtefactResolution.allLibraryDeps(build)
    assertEquals(libraryDeps, Set(
      Dep("io.monix", "minitest_2.12", "2.3.2"),
      Dep("io.monix", "minitest_sjs0.6_2.12", "2.3.2")))
  }
}
