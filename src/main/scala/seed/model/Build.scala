package seed.model

import java.nio.file.Path

import seed.artefact.MavenCentral

case class Build(`import`: List[Path] = List(),
                 project: Build.Project,
                 resolvers: Build.Resolvers = Build.Resolvers(),
                 module: Map[String, Build.Module])

object Build {
  case class Dep(organisation: String, artefact: String, version: String)
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
                    scalaDeps: List[Dep] = List(),
                    javaDeps: List[Dep] = List(),
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
