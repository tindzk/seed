package seed.artefact

import minitest.SimpleTestSuite
import seed.Log
import seed.artefact.SemanticVersioning._
import scala.math.Ordering.Implicits._

import scala.util.Random

object SemanticVersioningSpec extends SimpleTestSuite {
  private val versionOrdering =
    new SemanticVersioning(Log.urgent).stringVersionOrdering

  test("Parse semantic versions") {
    assertEquals(parseVersion("1"), Some(Version(1)))
    assertEquals(parseVersion("1.0.0-beta"), Some(Version(1, 0, 0, Some(Beta))))
    assertEquals(parseVersion("1.0.0"), Some(Version(1, 0, 0)))
    assertEquals(
      parseVersion("1.0.0-rc"),
      Some(Version(1, 0, 0, Some(ReleaseCandidate), List()))
    )
    assertEquals(
      parseVersion("1.0.0-rc.1"),
      Some(Version(1, 0, 0, Some(ReleaseCandidate), List(1)))
    )

    // From https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/maven-metadata.xml
    assertEquals(
      parseVersion("1.3.0.201202151440-r"),
      Some(Version(1, 3, 0, None, List(201202151440L)))
    )
  }

  test("Detect pre-releases") {
    assert(!isPreRelease("1"))
    assert(!isPreRelease("1.0.1"))
    assert(isPreRelease("1.0.0-beta"))
    assert(isPreRelease("1.0.0-rc"))
    assert(!isPreRelease("1.3.0.201202151440-r"))
    assert(isPreRelease("2.8.0.Beta1-RC1"))
    assert(isPreRelease("2.8.0.r18462-b20090811081019"))
  }

  test("Parse Scala's semantic versioning") {
    assertEquals(
      parseVersion("1.0.0-M3"),
      Some(Version(1, 0, 0, Some(Milestone), List(3)))
    )
    assertEquals(
      parseVersion("3.2.0-SNAP10"),
      Some(Version(3, 2, 0, Some(Snapshot), List(10)))
    )
    assertEquals(
      parseVersion("2.12.0-RC2"),
      Some(Version(2, 12, 0, Some(ReleaseCandidate), List(2)))
    )
    assertEquals(
      parseVersion("2.11.11-bin-typelevel-4"),
      Some(Version(2, 11, 11, None, List(4)))
    )
    assertEquals(
      parseVersion("0.1.2-SNAPSHOT"),
      Some(Version(0, 1, 2, Some(Snapshot)))
    )
    assertEquals(
      parseVersion("2.9.0.RC4"),
      Some(Version(2, 9, 0, Some(ReleaseCandidate), List(4)))
    )
    assertEquals(parseVersion("2.9.0-1"), Some(Version(2, 9, 0, None, List(1))))
    assertEquals(
      parseVersion("2.8.0.Beta1-RC1"),
      Some(Version(2, 8, 0, Some(Beta), List(1)))
    )
    assertEquals(
      parseVersion("2.8.0.r18462-b20090811081019"),
      Some(Version(2, 8, 0, Some(Snapshot), List(18462)))
    )

    // From https://semver.org/:
    // [T]wo versions that differ only in the build metadata, have the same
    // precedence. Examples: 1.0.0-alpha+001, 1.0.0+20130313144700, 1.0.0-beta+exp.sha.5114f85.
    assertEquals(parseVersion("1.0.0+417-35327239"), Some(Version(1, 0, 0)))
  }

  test("Order semantic versions") {
    val versions =
      List("1.0.0-beta", "1.0.0", "2.0.0-rc.1", "2.0.0-rc.2", "2.0.0")
    assertEquals(Random.shuffle(versions).sorted(versionOrdering), versions)
  }

  test("Parse pre-release parts") {
    assert(parsePreReleaseParts(List("RC")) == Some(ReleaseCandidate) -> List())
    assert(
      parsePreReleaseParts(List("RC1")) == Some(ReleaseCandidate) -> List(1)
    )
    assert(
      parsePreReleaseParts(List("RC1", "2")) == Some(ReleaseCandidate) -> List(
        1,
        2
      )
    )
  }

  test("Compare pre-release versions") {
    // From https://mvnrepository.com/artifact/org.scalactic/scalactic_2.12
    assert(parseVersion("3.0.5") > parseVersion("3.0.5-M1"))
    assert(parseVersion("3.0.6") > parseVersion("3.0.6-SNAP6"))
    assert(parseVersion("3.0.6-SNAP6") > parseVersion("3.0.6-SNAP5"))
    assert(parseVersion("3.0.6-SNAP10") > parseVersion("3.0.6-SNAP6"))
    assert(parseVersion("3.1.0-RC1") > parseVersion("3.1.0-SNAP13"))
  }

  test("Compare pre-release versions (2)") {
    // From https://mvnrepository.com/artifact/dev.zio/zio
    assert(parseVersion("1.0.0-RC18-2") > parseVersion("1.0.0-RC18-1"))
    assert(parseVersion("1.0.0-RC18-1") > parseVersion("1.0.0-RC18"))

    val versions = List(
      "1.0.0-RC12-1",
      "1.0.0-RC13",
      "1.0.0-RC18",
      "1.0.0-RC18-1",
      "1.0.0-RC18-2"
    )
    assertEquals(Random.shuffle(versions).sorted(versionOrdering), versions)
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
      "2.12.0"
    )

    assertEquals(Random.shuffle(versions).sorted(versionOrdering), versions)
  }

  test("Parse and order pre-release versions in Git notation") {
    // The second version is 14 commits away from the first one
    val versions = List("0.1.1", "0.1.1-14-g80f67e7", "0.1.1-20-gac74eb0g")
    val parsed   = versions.map(parseVersion)

    assertEquals(parsed(0), Some(Version(0, 1, 1, None, List())))
    assertEquals(parsed(1), Some(Version(0, 1, 1, Some(Commit), List(14))))
    assertEquals(parsed(2), Some(Version(0, 1, 1, Some(Commit), List(20))))

    assert(!isPreRelease(versions(0)))
    assert(isPreRelease(versions(1)))
    assert(isPreRelease(versions(2)))

    assertEquals(versions.reverse.sorted(versionOrdering), versions)
  }

  test("Order major versions") {
    val versions = (0 to 100).map(_.toString).toList
    assertEquals(Random.shuffle(versions).sorted(versionOrdering), versions)
  }

  test("Order minor versions") {
    val versions = for {
      a <- 0 to 10
      b <- 0 to 10
    } yield a + "." + b

    assertEquals(Random.shuffle(versions).sorted(versionOrdering), versions)
  }

  test("Order patch versions") {
    val versions = for {
      a <- 0 to 10
      b <- 0 to 10
      c <- 0 to 10
    } yield a + "." + b + "." + c

    assertEquals(Random.shuffle(versions).sorted(versionOrdering), versions)
  }
}
