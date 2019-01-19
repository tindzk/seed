package seed.artefact

import minitest.SimpleTestSuite
import seed.artefact.SemanticVersioning._

import scala.util.Random

object SemanticVersioningSpec extends SimpleTestSuite {
  test("Parse semantic versions") {
    assertEquals(parseVersion("1"), Some(Version(1)))
    assertEquals(parseVersion("1.0.0-beta"),
      Some(Version(1, 0, 0, Some(Beta))))
    assertEquals(parseVersion("1.0.0"),
      Some(Version(1, 0, 0)))
    assertEquals(parseVersion("1.0.0-rc"),
      Some(Version(1, 0, 0, Some(ReleaseCandidate), 0)))
    assertEquals(parseVersion("1.0.0-rc.1"),
      Some(Version(1, 0, 0, Some(ReleaseCandidate), 1)))
  }

  test("Detect pre-releases") {
    assert(!isPreRelease("1"))
    assert(!isPreRelease("1.0.1"))
    assert(isPreRelease("1.0.0-beta"))
    assert(isPreRelease("1.0.0-rc"))
  }

  test("Parse Scala's semantic versioning") {
    assertEquals(parseVersion("1.0.0-M3"),
      Some(Version(1, 0, 0, Some(Milestone), 3)))
    assertEquals(parseVersion("3.2.0-SNAP10"),
      Some(Version(3, 2, 0, Some(Snapshot), 10)))
    assertEquals(parseVersion("2.12.0-RC2"),
      Some(Version(2, 12, 0, Some(ReleaseCandidate), 2)))
    assertEquals(parseVersion("2.11.11-bin-typelevel-4"),
      Some(Version(2, 11, 11)))
    assertEquals(parseVersion("0.1.2-SNAPSHOT"),
      Some(Version(0, 1, 2, Some(Snapshot))))
    assertEquals(parseVersion("2.9.0.RC4"),
      Some(Version(2, 9, 0, Some(ReleaseCandidate), 4)))
    assertEquals(parseVersion("2.9.0-1"),
      Some(Version(2, 9, 0, None, 1)))
    assertEquals(parseVersion("2.8.0.Beta1-RC1"),
      Some(Version(2, 8, 0, Some(Beta), 1)))
    assertEquals(parseVersion("2.8.0.r18462-b20090811081019"),
      Some(Version(2, 8, 0, Some(Snapshot), 18462)))

    // From https://semver.org/:
    // [T]wo versions that differ only in the build metadata, have the same
    // precedence. Examples: 1.0.0-alpha+001, 1.0.0+20130313144700, 1.0.0-beta+exp.sha.5114f85.
    assertEquals(parseVersion("1.0.0+417-35327239"),
      Some(Version(1, 0, 0)))
  }

  test("Order semantic versions") {
    val versions = List(
      "1.0.0-beta",
      "1.0.0",
      "2.0.0-rc.1",
      "2.0.0-rc.2",
      "2.0.0")
    assertEquals(
      Random.shuffle(versions).sorted(versionOrdering),
      versions)
  }

  test("Order Scala's semantic versions") {
    val versions = List(
      "2.11.9",
      "2.11.10",
      "2.11.11",
      "2.11.12",
      "2.12.0-M1",
      "2.12.0-M2",
      "2.12.0-M3",
      "2.12.0-M4",
      "2.12.0-M5",
      "2.12.0-RC1",
      "2.12.0-RC2",
      "2.12.0")

    assertEquals(
      Random.shuffle(versions).sorted(versionOrdering),
      versions)
  }

  test("Order pre-release versions in Git notation") {
    // The second version is 14 commits away from the first one
    val versions = List(
      "0.1.1",
      "0.1.1-14-g80f67e7")

    // The only difference is in the preReleaseVersion
    assertEquals(
      parseVersion(versions(0)),
      Some(Version(0, 1, 1, None, 0)))
    assertEquals(
      parseVersion(versions(1)),
      Some(Version(0, 1, 1, None, 14)))

    assert(!isPreRelease(versions(0)))
    assert(isPreRelease(versions(1)))

    assertEquals(
      versions.reverse.sorted(versionOrdering),
      versions)
  }

  test("Order major versions") {
    val versions = (0 to 100).map(_.toString).toList
    assertEquals(
      Random.shuffle(versions).sorted(versionOrdering),
      versions)
  }

  test("Order minor versions") {
    val versions = for {
      a <- 0 to 10
      b <- 0 to 10
    } yield a + "." + b

    assertEquals(
      Random.shuffle(versions).sorted(versionOrdering),
      versions)
  }

  test("Order patch versions") {
    val versions = for {
      a <- 0 to 10
      b <- 0 to 10
      c <- 0 to 10
    } yield a + "." + b + "." + c

    assertEquals(
      Random.shuffle(versions).sorted(versionOrdering),
      versions)
  }
}
