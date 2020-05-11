package seed.artefact

import seed.Log
import seed.artefact.util.NaturalOrderComparator

object SemanticVersioning {
  sealed trait PreRelease
  case object Alpha            extends PreRelease
  case object Beta             extends PreRelease
  case object Milestone        extends PreRelease
  case object ReleaseCandidate extends PreRelease
  case object Nightly          extends PreRelease
  case object Snapshot         extends PreRelease
  case object Commit           extends PreRelease

  val PreReleaseOrdering = Map[PreRelease, Int](
    Alpha            -> 0,
    Beta             -> 1,
    Milestone        -> 2,
    Nightly          -> 3,
    Snapshot         -> 4,
    ReleaseCandidate -> 5,
    Commit           -> 7
  )

  private val Release = 6

  val PreReleaseMapping = List[(String, PreRelease)](
    "alpha"    -> Alpha,
    "beta"     -> Beta,
    "m"        -> Milestone,
    "rc"       -> ReleaseCandidate,
    "r"        -> Snapshot,
    "snapshot" -> Snapshot,
    "snap"     -> Snapshot,
    "g"        -> Commit // Git commit
  )

  case class Version(
    major: Int,
    minor: Int = 0,
    patch: Int = 0,
    preRelease: Option[PreRelease] = None,
    preReleaseVersion: List[Long] = List()
  )

  def isPreRelease(version: String): Boolean =
    parseVersion(version).fold(false)(_.preRelease.isDefined)

  def parseVersionParts(parts: List[String]): Option[(Int, Int, Int)] =
    if (!parts.forall(_.forall(_.isDigit))) None
    else if (parts.length == 1) Some((parts(0).toInt, 0, 0))
    else if (parts.length == 2) Some((parts(0).toInt, parts(1).toInt, 0))
    else if (parts.length == 3)
      Some((parts(0).toInt, parts(1).toInt, parts(2).toInt))
    else None

  def parsePreReleaseParts(
    parts: List[String]
  ): (Option[PreRelease], List[Long]) = {
    val preReleaseComponent =
      parts.find(p => p.length > 1 && p(0).isLetter).map(_.toLowerCase)
    val versionComponents =
      parts.filter(p => p.length > 0 && p.forall(_.isDigit)).map(_.toLong)

    preReleaseComponent
      .flatMap { prc =>
        PreReleaseMapping.view.flatMap {
          case (prefix, preRelease) =>
            if (!prc.startsWith(prefix)) None
            else {
              val version = prc.drop(prefix.length)
              val v =
                if (version.isEmpty || !version.forall(_.isDigit)) List()
                else List(version.toLong)
              Some((Some(preRelease), v ++ versionComponents))
            }
        }.headOption
      }
      .getOrElse((None, versionComponents))
  }

  def parseVersion(version: String): Option[Version] = {
    val versionWithoutMetaData = version.split('+').head
    val versionParts           = versionWithoutMetaData.split(Array('.', '-')).toList

    val (versionParts0, preReleaseParts0) = (
      versionParts.takeWhile(_.forall(_.isDigit)),
      versionParts.dropWhile(_.forall(_.isDigit))
    )

    val (versionParts1, preReleaseParts1) =
      (versionParts0.take(3), versionParts0.drop(3) ++ preReleaseParts0)

    val (parsedVersion, parsedPreRelease) =
      (parseVersionParts(versionParts1), parsePreReleaseParts(preReleaseParts1))

    parsedVersion.map {
      case (major, minor, patch) =>
        Version(major, minor, patch, parsedPreRelease._1, parsedPreRelease._2)
    }
  }

  def majorMinorVersion(version: String): String =
    version.reverse.dropWhile(_ != '.').tail.reverse

  private val comparator = new NaturalOrderComparator

  implicit private val preReleaseVersionOrdering = new Ordering[List[Long]] {
    // From https://stackoverflow.com/a/3137980/13300239
    override def compare(l1: List[Long], l2: List[Long]): Int = {
      for ((x, y) <- l1.zip(l2)) {
        val c = x.compare(y)
        if (c != 0) return c
      }

      l1.length - l2.length
    }
  }

  implicit val versionOrdering = new Ordering[Version] {
    override def compare(v1: Version, v2: Version): Int =
      implicitly[Ordering[(Int, Int, Int, Int, List[Long])]].compare(
        (
          v1.major,
          v1.minor,
          v1.patch,
          v1.preRelease.map(PreReleaseOrdering).getOrElse(Release),
          v1.preReleaseVersion
        ),
        (
          v2.major,
          v2.minor,
          v2.patch,
          v2.preRelease.map(PreReleaseOrdering).getOrElse(Release),
          v2.preReleaseVersion
        )
      )
  }
}

class SemanticVersioning(log: Log) {
  import SemanticVersioning._

  val stringVersionOrdering = new Ordering[String] {
    override def compare(x: String, y: String): Int =
      (parseVersion(x), parseVersion(y)) match {
        case (Some(v1), Some(v2)) => versionOrdering.compare(v1, v2)
        case (v1, _) =>
          val version = if (v1.isEmpty) x else y
          log.error(
            s"Could not parse version '$version'. Falling back to comparison by natural ordering..."
          )

          // Fall back to natural ordering comparison
          comparator.compare(x, y)
      }
  }
}
