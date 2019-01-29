package seed.artefact

import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Artefact, Platform}
import seed.Log
import seed.model.Build.VersionTag

import scala.util.Try

object MavenCentral {
  val Url = "https://repo1.maven.org/maven2"

  def urlForOrganisation(organisation: String): String = {
    val organisationPath = organisation.replaceAllLiterally(".", "/")
    s"$Url/$organisationPath/"
  }

  def urlForArtefactMetaData(organisation: String, artefactName: String): String =
    urlForOrganisation(organisation) + s"$artefactName/maven-metadata.xml"

  def requestHttp(url: String): String = {
    Log.debug(s"Requesting $url...")
    scalaj.http.Http(url).asString.body
  }

  def isArtefactEligible(stable: Boolean)(version: String): Boolean =
    !stable || (stable && !SemanticVersioning.isPreRelease(version))

  /** @return If there no stable version was published for the artefact, return
   *          all unstable versions
   */
  def parseVersionsXml(body: String, stable: Boolean): List[String] = {
    val versions = Try(pine.XmlParser.fromString(body))
      .toOption
      .toList
      .flatMap(_
        .byTagAll["version"]
        .flatMap(_.children.headOption)
        .collect { case pine.Text(t) => t }
        .sorted(SemanticVersioning.versionOrdering))

    if (stable && versions.exists(isArtefactEligible(stable)))
      versions.filter(isArtefactEligible(stable))
    else versions
  }

  def fetchOrganisationArtefacts(organisation: String): List[String] = {
    val response = requestHttp(urlForOrganisation(organisation))
    response.split("\n")
      .toList
      .filter(_.startsWith("<a href=\""))
      .map(_.drop("<a href=\"".length))
      .map(_.takeWhile(_ != '/'))
  }

  /** @param stable Only consider stable artefacts (as opposed to pre-releases)
    * @return Found versions in ascending order
    */
  def fetchLibraryArtefacts(artefact: Artefact, stable: Boolean): List[
    (Platform, PlatformVersion, CompilerVersion)
  ] =
    fetchOrganisationArtefacts(artefact.organisation)
      .filter(_.startsWith(artefact.name))
      .map(_.drop(artefact.name.length + 1))
      .filter(a =>
        a.headOption.exists(_.isDigit) ||
        a.startsWith("sjs") ||
        a.startsWith("native")
      ).map(parseArtefactName)
       .filter(a => isArtefactEligible(stable)(a._2) &&
                    isArtefactEligible(stable)(a._3))
       .sortBy(_._2)(SemanticVersioning.versionOrdering)

  type PlatformVersion = String
  type CompilerVersion = String
  /** Derive from artefact name, supported platforms and compilers */
  def parseArtefactName(version: String):
    (Platform, PlatformVersion, CompilerVersion) = {

    if (version.startsWith("sjs"))
      (JavaScript,
       version.drop("sjs".length).takeWhile(_ != '_'),
       version.reverse.takeWhile(_ != '_').reverse)
    else if (version.startsWith("native"))
      (Native,
        version.drop("native".length).takeWhile(_ != '_'),
        version.reverse.takeWhile(_ != '_').reverse)
    else
      (JVM, version, version)
  }

  def fetchCompilerVersions(artefact: Artefact, stable: Boolean): List[String] = {
    require(artefact.versionTag.isEmpty)
    val url = urlForArtefactMetaData(artefact.organisation, artefact.name)
    parseVersionsXml(requestHttp(url), stable)
  }

  def fetchPlatformCompilerVersions(artefact: Artefact, stable: Boolean): List[String] = {
    require(artefact.versionTag.contains(VersionTag.Full))
    fetchLibraryArtefacts(artefact, stable).map(_._3).distinct
  }

  def trimCompilerVendor(version: String): String =
    version.replace("-bin-typelevel-4", "")

  def trimCompilerVersion(version: String): String =
    if (SemanticVersioning.isPreRelease(version)) version
    else {
      val result = trimCompilerVendor(version)
      if (result.count(_ == '.') == 1) result
      else {
        val idx1 = result.indexOf('.')
        val idx2 = result.indexOf('.', idx1 + 1)
        result.take(idx2)
      }
    }

  def formatArtefactName(artefactName: String,
                         versionTag: VersionTag,
                         platform: Platform,
                         platformVersion: PlatformVersion,
                         compilerVersion: CompilerVersion
                        ): String =
    versionTag match {
      case VersionTag.PlatformBinary =>
        val trimmedCompilerVersion = trimCompilerVersion(compilerVersion)

        val version = platform match {
          case JVM        => trimmedCompilerVersion
          case JavaScript => "sjs" + trimCompilerVersion(platformVersion) +
                             "_" + trimmedCompilerVersion
          case Native     => "native" + trimCompilerVersion(platformVersion) +
                             "_" + trimmedCompilerVersion
        }

        artefactName + "_" + version

      case VersionTag.Full =>
        val trimmedCompilerVersion = trimCompilerVendor(compilerVersion)
        artefactName + "_" + trimmedCompilerVersion

      case VersionTag.Binary =>
        val trimmedCompilerVersion = trimCompilerVersion(compilerVersion)
        artefactName + "_" + trimmedCompilerVersion
    }

  def fetchVersions(artefact: Artefact,
                    platform: Platform,
                    platformVersion: PlatformVersion,
                    compilerVersion: CompilerVersion,
                    stable: Boolean
                   ): List[String] = {
    val artefactName =
      artefact.versionTag.fold(artefact.name)(vt =>
        formatArtefactName(artefact.name, vt, platform, platformVersion,
          compilerVersion))
    val url = urlForArtefactMetaData(artefact.organisation, artefactName)

    parseVersionsXml(requestHttp(url), stable)
  }
}
