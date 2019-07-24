package seed.cli

import minitest.SimpleTestSuite
import seed.Log
import seed.model.{Organisation, Platform}
import toml.Node.NamedTable
import toml.Value.Str

object ScaffoldSpec extends SimpleTestSuite {
  private val scaffold = new Scaffold(Log.urgent, silent = true)

  test("Create build file one non-JVM platform") {
    val result = scaffold.generateBuildFile(
      "example",
      stable = true,
      organisation = Organisation.Lightbend,
      platforms = Set(Platform.Native),
      testFrameworks = Set()
    )

    val project = result.nodes(0).asInstanceOf[NamedTable]
    assert(project.values("scalaVersion").asInstanceOf[Str].value.nonEmpty)
    assert(project.values("scalaNativeVersion").asInstanceOf[Str].value.nonEmpty)
    assert(!project.values.isDefinedAt("testFrameworks"))

    val module = result.nodes(1).asInstanceOf[NamedTable]
    assertEquals(module.ref, List("module", "example", "native"))

    assertEquals(result.nodes.length, 2)
  }

  test("Fetch compatible compiler versions for Typelevel Scala (stable)") {
    val compilerVersions = scaffold.fetchCompilerVersions(
      "org.typelevel", Set(Platform.JavaScript), stable = true)

    assertEquals(compilerVersions,
      Map(
        Platform.JVM -> List("2.11.7", "2.11.8", "2.11.11-bin-typelevel-4",
          "2.12.0", "2.12.1", "2.12.2-bin-typelevel-4",
          "2.12.3-bin-typelevel-4", "2.12.4-bin-typelevel-4"),
        Platform.JavaScript -> List(
          "2.11.7", "2.11.8", "2.11.11", "2.12.0", "2.12.1", "2.12.2", "2.12.3",
          "2.12.4")))
  }

  test("Fetch compatible compiler versions for Typelevel Scala (unstable)") {
    val compilerVersions = scaffold.fetchCompilerVersions(
      "org.typelevel", Set(Platform.JavaScript), stable = false)

    assertEquals(compilerVersions,
      Map(
        Platform.JVM -> List("2.11.7", "2.11.8", "2.11.11-bin-typelevel-4",
          "2.12.0-RC2", "2.12.0", "2.12.1", "2.12.2-bin-typelevel-4",
          "2.12.3-bin-typelevel-4", "2.12.4-bin-typelevel-4",
          "2.13.0-M2-bin-typelevel-4"
        ),
        Platform.JavaScript -> List(
          "2.11.7", "2.11.8", "2.11.11", "2.12.0-RC2", "2.12.0", "2.12.1",
          "2.12.2", "2.12.3", "2.12.4", "2.13.0-M2")))
  }
}
