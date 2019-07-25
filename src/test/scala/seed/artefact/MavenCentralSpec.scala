package seed.artefact

import minitest.SimpleTestSuite
import seed.model.Platform.JVM
import seed.Log

object MavenCentralSpec extends SimpleTestSuite {
  test("Parse library artefact versions") {
    val artefacts = List(
      "scribe-slf4j18_2.11",
      "scribe-slf4j18_2.12",
      "scribe-slf4j18_2.13",

      // Only these should match
      "scribe-slf4j_2.11",
      "scribe-slf4j_2.12",
      "scribe-slf4j_2.13",
      "scribe-slf4j_2.13.0-RC2")

    def parse(stable: Boolean) =
      MavenCentral.parseLibraryArtefacts(
        artefacts,
        "scribe-slf4j",
        stable,
        Log.urgent)

    assertEquals(parse(stable = false), List(
      (JVM, "2.11", "2.11"),
      (JVM, "2.12", "2.12"),
      (JVM, "2.13.0-RC2", "2.13.0-RC2"),
      (JVM, "2.13", "2.13")))

    assertEquals(parse(stable = true), List(
      (JVM, "2.11", "2.11"),
      (JVM, "2.12", "2.12"),
      (JVM, "2.13", "2.13")))
  }
}
