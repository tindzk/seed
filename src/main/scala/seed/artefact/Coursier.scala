package seed.artefact

import java.io.{File, OutputStreamWriter}
import java.nio.file.{Path, Paths}

import coursier.Cache.Logger
import coursier.core.{Classifier, ModuleName, Organization}
import coursier.ivy.IvyRepository
import coursier.util.{Gather, Task}
import coursier.{Artifact, Cache, CachePath, Dependency, Fetch, MavenRepository, Module, Resolution, TermDisplay}
import seed.cli.util.Ansi
import seed.model.Build.{Dep, Resolvers}
import seed.model.Platform
import seed.{Log, model}

import scala.concurrent.ExecutionContext.Implicits.global

object Coursier {
  type Artefact    = Artifact
  type ArtefactUrl = String

  val ArtefactSuffix  : String = ".jar"
  val DefaultIvyPath  : Path   = Paths.get(sys.props("user.home")).resolve(".ivy2").resolve("local")
  val DefaultCachePath: Path   = CachePath.defaultCacheDirectory.toPath

  case class ResolutionResult(resolution: Resolution,
                              artefacts: Map[ArtefactUrl, File])

  private var logger: Option[Logger] = None

  def initLogger(): Unit = {
    require(logger.isEmpty)

    val termDisplay = new TermDisplay(new OutputStreamWriter(System.err))
    termDisplay.init()

    logger = Some(termDisplay)
  }

  def hasDep(resolutionResult: Coursier.ResolutionResult, dep: Dep): Boolean =
    resolutionResult.resolution.dependencies.exists(d =>
      d.module.organization.value == dep.organisation &&
      d.module.name.value == dep.artefact &&
      d.version == dep.version)

  def coursierDependencies(deps: Set[Dep]): Set[Dependency] =
    deps.map(r => Dependency(Module(Organization(r.organisation), ModuleName(r.artefact)), r.version))

  def resolve(all: Set[Dep], resolvers: Resolvers, ivyPath: Path, cachePath: Path): Resolution =
    if (all.isEmpty) Resolution.empty
    else {
      val organisations = all.map(_.organisation).toList.sorted.map(Ansi.italic).mkString(", ")
      Log.debug(s"Resolving ${Ansi.bold(all.size.toString)} dependencies from $organisations...")

      val mapped = coursierDependencies(all)
      val start  = Resolution(mapped)

      val ivy = resolvers.ivy.map { resolver =>
        val pattern = resolver.pattern.fold(coursier.ivy.Pattern.default)(p =>
          IvyRepository.parse(p) match {
            case Left(error) =>
              Log.error(s"Could not parse Ivy pattern ${Ansi.italic(p)}: $error")
              sys.exit(1)
            case Right(parsed) => parsed.pattern
          })

        IvyRepository.fromPattern(
          resolver.url +: pattern,
          dropInfoAttributes = true)
      }

      val ivyRepository = IvyRepository.fromPattern(
        (ivyPath.toUri.toString + "/") +: coursier.ivy.Pattern.default,
        dropInfoAttributes = true)

      val repositories = ivyRepository +:
                         (resolvers.maven.map(MavenRepository(_)) ++ ivy)

      val fetch = Fetch.from(repositories, Cache.fetch[Task](
        logger = logger, cache = cachePath.toFile))
      val resolution = start.process.run(fetch).unsafeRun()

      val errors = resolution.errors
      if (errors.nonEmpty) {
        Log.error("Some dependencies could not be resolved:")
        errors.foreach { case ((module, _), _) =>
          Log.error(s"  - ${module.name} in ${module.organization}")
        }
        sys.exit(1)
      }

      resolution
    }

  def localArtefacts(artefacts: Seq[Artefact],
                     cache: Path): Map[ArtefactUrl, File] = {
    val localArtefacts = Gather[Task].gather(
      artefacts.map(artefact =>
        Cache.file[Task](artefact, logger = logger, cache = cache.toFile)
          .run.map(result => artefact.url -> result))
    ).unsafeRun()

    if (localArtefacts.exists(_._2.isLeft))
      Log.error("Failed to download: " + localArtefacts.filter(_._2.isLeft))

    localArtefacts
      .toMap
      .collect {
        case (k, v) if v.isRight && v.exists(_.getName.endsWith(ArtefactSuffix)) =>
          k -> v.toOption.get
      }
  }

  def resolveAndDownload(deps: Set[Dep],
                         resolvers: Resolvers,
                         ivyPath: Path,
                         cachePath: Path,
                         optionalArtefacts: Boolean): ResolutionResult = {
    val resolution = resolve(deps, resolvers, ivyPath, cachePath)
    val artefacts = resolution.dependencyArtifacts(
      Some(overrideClassifiers(
        sources = optionalArtefacts,
        javaDoc = optionalArtefacts))).map(_._3).toList
    ResolutionResult(resolution, localArtefacts(artefacts, cachePath))
  }

  def resolveSubset(resolution: Resolution,
                    deps: Set[Dep],
                    optionalArtefacts: Boolean): List[(Classifier, Artefact)] =
    resolution.subset(coursierDependencies(deps))
      .dependencyArtifacts(
        Some(
          overrideClassifiers(
            sources = optionalArtefacts,
            javaDoc = optionalArtefacts))
      ).map(a => (a._2.classifier, a._3)).toList

  def overrideClassifiers(sources: Boolean, javaDoc: Boolean): Seq[Classifier] =
    Seq(Classifier.empty) ++
    (if (sources) Seq(Classifier.sources) else Seq()) ++
    (if (javaDoc) Seq(Classifier.javadoc) else Seq())

  /** Resolves requested libraries and their dependencies */
  def localArtefacts(result: ResolutionResult,
                     all: Set[Dep],
                     optionalArtefacts: Boolean = false
                    ): List[model.Resolution.Artefact] =
    resolveSubset(result.resolution, all, optionalArtefacts)
      .groupBy(x => x._2.url.take(x._2.url.lastIndexOf('/')))
      .values
      .toList
      .filter(x =>
        x.exists(x => x._1 == Classifier.empty && x._2.url.endsWith(".jar"))
      ).map { a =>
        val jar = a.find(_._1 == Classifier.empty).get._2.url
        val doc = a.find(_._1 == Classifier.javadoc).map(_._2.url)
        val src = a.find(_._1 == Classifier.sources).map(_._2.url)

        model.Resolution.Artefact(
          libraryJar = result.artefacts(jar).toPath,
          javaDocJar = doc.map(result.artefacts(_).toPath),
          sourcesJar = src.map(result.artefacts(_).toPath))
      }

  /** Resolves path to JAR file of requested artefact */
  def artefactPath(result: ResolutionResult,
                   artefact: model.Artefact,
                   platform: Platform,
                   platformVersion: MavenCentral.PlatformVersion,
                   compilerVersion: MavenCentral.CompilerVersion,
                   version: String): Option[Path] = {
    val name = MavenCentral.formatArtefactName(artefact, platform,
      platformVersion, compilerVersion)

    result.resolution.dependencyArtifacts().find { case (dep, attr, art) =>
      dep.module.name.value == name
    }.map(a => result.artefacts(a._3.url).toPath)
  }
}
