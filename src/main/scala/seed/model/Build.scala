package seed.model

import java.nio.file.Path

import seed.artefact.MavenCentral

case class Build(`import`: List[Path] = List(),
                 project: Build.Project,
                 resolvers: Build.Resolvers = Build.Resolvers(),
                 module: Map[String, Build.Module])

object Build {
  sealed trait Dep {
    def organisation: String
    def artefact: String
    def version: String
  }

  case class JavaDep(organisation: String, artefact: String, version: String)
    extends Dep

  sealed trait VersionTag
  object VersionTag {
    /** Binary Scala version (e.g. 2.12). This behaves as Full if the Scala
      * version is a pre-release (e.g. 2.12.8-M3).
      */
    case object Binary extends VersionTag

    /** Full Scala version (e.g. 2.11.11) */
    case object Full extends VersionTag

    /** Platform name including binary Scala version (e.g. native0.3_2.11) */
    case object PlatformBinary extends VersionTag
  }

  case class ScalaDep(organisation: String,
                      artefact: String,
                      version: String,
                      versionTag: VersionTag = VersionTag.PlatformBinary
                     ) extends Dep

  case class Project(scalaVersion: String,
                     scalaJsVersion: Option[String] = None,
                     scalaNativeVersion: Option[String] = None,
                     scalaOptions: List[String] = List(),
                     scalaOrganisation: String = Organisation.Lightbend.packageName,
                     testFrameworks: List[String] = List())

  case class Module(scalaVersion: Option[String] = None,
                    root: Option[Path] = None,
                    sources: List[Path] = List(),
                    resources: List[Path] = List(),
                    scalaDeps: List[ScalaDep] = List(),
                    javaDeps: List[JavaDep] = List(),
                    moduleDeps: List[String] = List(),
                    mainClass: Option[String] = None,
                    targets: List[Platform] = List(),
                    output: Option[Path] = None,

                    // JavaScript
                    jsdom: Boolean = false,
                    emitSourceMaps: Boolean = true,

                    // Native
                    gc: Option[String] = None,
                    targetTriple: Option[String] = None,
                    clang: Option[Path] = None,
                    clangpp: Option[Path] = None,
                    linkerOptions: Option[List[String]] = None,
                    compilerOptions: Option[List[String]] = None,
                    linkStubs: Boolean = false,

                    test: Option[Module] = None,
                    js: Option[Module] = None,
                    jvm: Option[Module] = None,
                    native: Option[Module] = None)

  case class IvyResolver(url: String, pattern: Option[String] = None)

  case class Resolvers(maven: List[String] = List(MavenCentral.Url),
                       ivy: List[IvyResolver] = List())
}
