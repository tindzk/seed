package seed.artefact

import minitest.SimpleTestSuite
import seed.model.Platform.JVM
import seed.Log
import seed.model.Build.VersionTag
import seed.model.Platform

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
      "scribe-slf4j_2.13.0-RC2"
    )

    def parse(stable: Boolean) =
      MavenCentral.parseLibraryArtefacts(
        artefacts,
        "scribe-slf4j",
        stable,
        Log.urgent
      )

    assertEquals(
      parse(stable = false),
      List(
        (JVM, "2.11", "2.11"),
        (JVM, "2.12", "2.12"),
        (JVM, "2.13.0-RC2", "2.13.0-RC2"),
        (JVM, "2.13", "2.13")
      )
    )

    assertEquals(
      parse(stable = true),
      List((JVM, "2.11", "2.11"), (JVM, "2.12", "2.12"), (JVM, "2.13", "2.13"))
    )
  }

  test("Format Scala.js artefact names") {
    val sjs06 = MavenCentral.formatArtefactName(
      "slinky-core",
      VersionTag.PlatformBinary,
      Platform.JavaScript,
      "0.6.32",
      "2.13.1"
    )
    assertEquals(sjs06, "slinky-core_sjs0.6_2.13")

    val sjs10rc2 = MavenCentral.formatArtefactName(
      "slinky-core",
      VersionTag.PlatformBinary,
      Platform.JavaScript,
      "1.0-RC2",
      "2.13.1"
    )
    assertEquals(sjs10rc2, "slinky-core_sjs1.0-RC2_2.13")

    val sjs10 = MavenCentral.formatArtefactName(
      "slinky-core",
      VersionTag.PlatformBinary,
      Platform.JavaScript,
      "1.0.1",
      "2.13.1"
    )
    assertEquals(sjs10, "slinky-core_sjs1_2.13")
  }
}
