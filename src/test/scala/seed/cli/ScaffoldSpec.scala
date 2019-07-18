package seed.cli

import minitest.SimpleTestSuite
import seed.Log
import seed.model.{Organisation, Platform}
import toml.Node.NamedTable
import toml.Value.Str

object ScaffoldSpec extends SimpleTestSuite {
  test("Create build file one non-JVM platform") {
    val scaffold = new Scaffold(Log.urgent, silent = true)
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
}
