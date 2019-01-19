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

  val PreReleaseOrdering = Map[PreRelease, Int](
    Alpha -> 0,
    Beta  -> 1,
    Milestone -> 2,
    ReleaseCandidate -> 3,
    Nightly -> 4,
    Snapshot -> 5)

  private val Release = 6

  val PreReleaseMapping = List[(String, PreRelease)](
    "alpha" -> Alpha,
    "beta" -> Beta,
    "m" -> Milestone,
    "rc" -> ReleaseCandidate,
    "r" -> Snapshot,
    "snapshot" -> Snapshot,
    "snap" -> Snapshot)

  case class Version(major: Int,
                     minor: Int = 0,
                     patch: Int = 0,
                     preRelease: Option[PreRelease] = None,
                     preReleaseVersion: Int = 0)

  def isPreRelease(version: String): Boolean =
    parseVersion(version).fold(false)(v =>
      v.preRelease.isDefined || v.preReleaseVersion > 0)

  def parseVersionParts(parts: List[String]): Option[(Int, Int, Int)] =
    if (!parts.forall(_.forall(_.isDigit))) None
    else if (parts.length == 1) Some((parts(0).toInt, 0, 0))
    else if (parts.length == 2) Some((parts(0).toInt, parts(1).toInt, 0))
    else if (parts.length == 3) Some((parts(0).toInt, parts(1).toInt, parts(2).toInt))
    else None

  def parsePreReleaseParts(parts: List[String]): (Option[PreRelease], Int) = {
    if (parts.isEmpty) (None, 0)
    else if (parts.head.forall(_.isDigit)) (None, parts.head.toInt)
    else {
      val prp = parts.head.toLowerCase

      PreReleaseMapping.view.flatMap { case (prefix, preRelease) =>
        if (!prp.startsWith(prefix)) None
        else {
          val version = prp.drop(prefix.length)
          if (version.nonEmpty && version.forall(_.isDigit))
            Some((Some(preRelease), version.toInt))
          else if (parts.length >= 2 && parts(1).nonEmpty && parts(1).forall(_.isDigit))
            Some((Some(preRelease), parts(1).toInt))
          else Some((Some(preRelease), 0))
        }
      }.headOption.getOrElse((None, 0))
    }
  }

  def parseVersion(version: String): Option[Version] = {
    val versionWithoutMetaData = version.split('+').head
    val versionParts = versionWithoutMetaData.split(Array('.', '-')).toList

    val (versionParts0, preReleaseParts0) = (
      versionParts.takeWhile(_.forall(_.isDigit)),
      versionParts.dropWhile(_.forall(_.isDigit)))

    val (versionParts1, preReleaseParts1) =
      (versionParts0.take(3), versionParts0.drop(3) ++ preReleaseParts0)

    val (parsedVersion, parsedPreRelease) =
      (parseVersionParts(versionParts1),
       parsePreReleaseParts(preReleaseParts1))

    parsedVersion.map { case (major, minor, patch) =>
      Version(major, minor, patch, parsedPreRelease._1, parsedPreRelease._2)
    }
  }

  private val comparator = new NaturalOrderComparator

  val versionOrdering = new Ordering[String] {
    override def compare(x: String, y: String): Int = {
      (parseVersion(x), parseVersion(y)) match {
        case (Some(v1), Some(v2)) =>
          implicitly[Ordering[(Int, Int, Int, Int, Int)]].compare(
            (v1.major, v1.minor, v1.patch,
              v1.preRelease.map(PreReleaseOrdering).getOrElse(Release),
              v1.preReleaseVersion),
            (v2.major, v2.minor, v2.patch,
              v2.preRelease.map(PreReleaseOrdering).getOrElse(Release),
              v2.preReleaseVersion))
        case (v1, _) =>
          val version = if (v1.isEmpty) x else y
          Log.error(s"Could not parse version '$version'. Falling back to comparison by natural ordering...")

          // Fall back to natural ordering comparison
          comparator.compare(x, y)
      }
    }
  }
}
