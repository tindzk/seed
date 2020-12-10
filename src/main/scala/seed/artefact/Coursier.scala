package seed.artefact

import java.io.File
import java.nio.file.{Path, Paths}

import coursier._
import coursier.ivy.IvyRepository
import coursier.paths.CachePath
import coursier.cache._
import coursier.cache.loggers._
import coursier.error.ResolutionError.CantDownloadModule
import coursier.params.ResolutionParams
import coursier.util.{Gather, Task}
import seed.artefact.ArtefactResolution.{ScalaOrganisation, ScalaVersion}
import seed.cli.util.Ansi
import seed.model.Build.{JavaDep, Resolvers}
import seed.model.Platform
import seed.{Log, model}

import scala.concurrent.ExecutionContext.Implicits.global

object Coursier {
  type Artefact    = coursier.util.Artifact
  type ArtefactUrl = String

  val ArtefactSuffix: String = ".jar"
  val DefaultIvyPath: Path =
    Paths.get(sys.props("user.home")).resolve(".ivy2").resolve("local")
  val DefaultCachePath: Path = CachePath.defaultCacheDirectory.toPath

  case class ResolutionResult(
    resolution: Resolution,
    artefacts: Map[ArtefactUrl, File]
  )

  def withLogger[T](silent: Boolean)(f: CacheLogger => T): T =
    if (silent) f(CacheLogger.nop)
    else {

      /** TODO Use [[seed.cli.util.ProgressBars]] **/
      val logger =
        RefreshLogger.create(System.err, ProgressBarRefreshDisplay.create())
      logger.init()
      val result = f(logger)
      logger.stop()
      result
    }

  def hasDep(
    resolutionResult: Coursier.ResolutionResult,
    dep: JavaDep
  ): Boolean =
    resolutionResult.resolution.dependencies.exists(
      d =>
        d.module.organization.value == dep.organisation &&
          d.module.name.value == dep.artefact &&
          d.version == dep.version
    )

  def coursierDependencies(deps: Set[JavaDep]): Seq[coursier.core.Dependency] =
    deps
      .map(
        r =>
          Dependency(
            Module(Organization(r.organisation), ModuleName(r.artefact)),
            r.version
          )
      )
      .toList

  def resolve(
    all: Set[JavaDep],
    forceScala: (ScalaOrganisation, ScalaVersion),
    resolvers: Resolvers,
    ivyPath: Path,
    cachePath: Path,
    silent: Boolean,
    log: Log
  ): Resolution =
    if (all.isEmpty) Resolution.empty
    else {
      val organisations =
        all.map(_.organisation).toList.sorted.map(Ansi.italic).mkString(", ")
      log.debug(
        s"Resolving ${Ansi.bold(all.size.toString)} dependencies from $organisations..."
      )

      val mapped = coursierDependencies(all)

      val ivy = resolvers.ivy.map { resolver =>
        val pattern = resolver.pattern.fold(coursier.ivy.Pattern.default)(
          p =>
            IvyRepository.parse(p) match {
              case Left(error) =>
                log.error(
                  s"Could not parse Ivy pattern ${Ansi.italic(p)}: $error"
                )
                sys.exit(1)
              case Right(parsed) => parsed.pattern
            }
        )

        IvyRepository.fromPattern(
          resolver.url +: pattern,
          dropInfoAttributes = true
        )
      }

      val ivyRepository = IvyRepository.fromPattern(
        (ivyPath.toUri.toString + "/") +: coursier.ivy.Pattern.default,
        dropInfoAttributes = true
      )

      val repositories = ivyRepository +:
        (resolvers.maven.map(MavenRepository(_)) ++ ivy)

      val (scalaOrganisation, scalaVersion) = forceScala
      val scalaOrg                          = Organization(scalaOrganisation)
      val forceVersions =
        Seq(
          Module(scalaOrg, ModuleName("scala-library"))  -> scalaVersion,
          Module(scalaOrg, ModuleName("scala-reflect"))  -> scalaVersion,
          Module(scalaOrg, ModuleName("scala-compiler")) -> scalaVersion,
          Module(scalaOrg, ModuleName("scalap"))         -> scalaVersion
        )

      val resolutionParams =
        ResolutionParams()
          .withForceVersion(forceVersions.toMap)
          .withTypelevel {
            // TODO Support other organisations too
            val org = model.Organisation.resolve(scalaOrganisation)
            if (org.isEmpty)
              log.error(
                s"Non-standard organisation $scalaOrganisation is not supported by Coursier"
              )
            org.contains(model.Organisation.Typelevel)
          }

      val resolution: Resolution =
        try Resolve()
          .withDependencies(mapped)
          .withRepositories(repositories)
          .withResolutionParams(resolutionParams)
          .run()
        catch {
          case e: CantDownloadModule =>
            log.error(
              s"Could not resolve dependency ${e.module.name.value} in ${e.module.organization.value}"
            )
            sys.exit(1)
        }

      val errors = resolution.errors
      if (errors.nonEmpty) {
        log.error("Some dependencies could not be resolved:")
        errors.foreach {
          case ((module, _), _) =>
            log.error(
              s"  - ${module.name.value} in ${module.organization.value}"
            )
        }
        sys.exit(1)
      }

      withLogger(silent) { l =>
        val fileCache = FileCache[Task]()
          .withLocation(cachePath.toFile)
          .withLogger(l)

        val files = Fetch()
          .withCache(fileCache)
          .withDependencies(mapped)
          .withRepositories(repositories)
          .run()
      }

      resolution
    }

  def localArtefacts(
    artefacts: Seq[Artefact],
    cache: Path,
    silent: Boolean,
    log: Log
  ): Map[ArtefactUrl, File] = {
    val localArtefacts = withLogger(silent) { l =>
      val fileCache = FileCache[Task]()
        .withLocation(cache.toFile)
        .withLogger(l)

      Gather[Task]
        .gather(
          artefacts.map { artefact =>
            fileCache.file(artefact).run.map(artefact.url -> _)
          }
        )
        .unsafeRun()
    }

    val failures = localArtefacts.filter(_._2.isLeft)
    if (failures.nonEmpty) {
      log.error("Some artefacts could not be downloaded:")
      failures.foreach {
        case (_, Left(b)) => log.error(" - " + b.describe)
        case _            =>
      }
    }

    localArtefacts.toMap
      .collect {
        case (k, v)
            if v.isRight && v.exists(_.getName.endsWith(ArtefactSuffix)) =>
          k -> v.toOption.get
      }
  }

  def resolveAndDownload(
    deps: Set[JavaDep],
    forceScala: (ScalaOrganisation, ScalaVersion),
    resolvers: Resolvers,
    ivyPath: Path,
    cachePath: Path,
    optionalArtefacts: Boolean,
    silent: Boolean,
    log: Log
  ): ResolutionResult = {
    val resolution =
      resolve(deps, forceScala, resolvers, ivyPath, cachePath, silent, log)

    val artefacts = resolution
      .dependencyArtifacts(
        Some(
          overrideClassifiers(
            sources = optionalArtefacts,
            javaDoc = optionalArtefacts
          )
        )
      )
      .map(_._3)
      .toList

    ResolutionResult(
      resolution,
      localArtefacts(artefacts, cachePath, silent, log)
    )
  }

  def resolveSubset(
    resolution: Resolution,
    deps: Set[JavaDep],
    optionalArtefacts: Boolean
  ): Map[JavaDep, List[(Classifier, Artefact)]] = {
    val result =
      resolution
        .subset(coursierDependencies(deps))
        .dependencyArtifacts(
          Some(
            overrideClassifiers(
              sources = optionalArtefacts,
              javaDoc = optionalArtefacts
            )
          )
        )

    val missing = deps.filter(
      d =>
        !result
          .map(_._1.module)
          .exists(
            m =>
              m.organization.value == d.organisation &&
                m.name.value == d.artefact
          )
    )
    require(
      missing.isEmpty,
      s"Missing dependencies in artefact resolution: $missing"
    )

    result
      .groupBy {
        case (dep, _, _) =>
          // Do not look up artefact in `deps` since `result` may contain additional
          // dependencies
          JavaDep(
            dep.module.organization.value,
            dep.module.name.value,
            dep.version
          )
      }
      .mapValues(_.map(a => (a._2.classifier, a._3)).toList)
  }

  def overrideClassifiers(sources: Boolean, javaDoc: Boolean): Seq[Classifier] =
    Seq(Classifier.empty) ++
      (if (sources) Seq(Classifier.sources) else Seq()) ++
      (if (javaDoc) Seq(Classifier.javadoc) else Seq())

  def localArtefacts(
    result: ResolutionResult,
    artefacts: List[(JavaDep, List[(coursier.Classifier, Coursier.Artefact)])]
  ): List[model.Resolution.Artefact] =
    artefacts.map {
      case (dep, a) =>
        // `a` also contains URLs to POM files
        val jars = a.filter(_._2.url.endsWith(".jar"))

        val jar =
          result.artefacts(jars.find(_._1 == Classifier.empty).get._2.url)
        val doc = jars
          .find(_._1 == Classifier.javadoc)
          .map(_._2.url)
          .flatMap(result.artefacts.get)
        val src = jars
          .find(_._1 == Classifier.sources)
          .map(_._2.url)
          .flatMap(result.artefacts.get)

        model.Resolution.Artefact(
          javaDep = dep,
          libraryJar = jar.toPath,
          javaDocJar = doc.map(_.toPath),
          sourcesJar = src.map(_.toPath)
        )
    }

  /** Resolves requested libraries and their dependencies */
  def localArtefacts(
    result: ResolutionResult,
    all: Set[JavaDep],
    optionalArtefacts: Boolean
  ): List[model.Resolution.Artefact] =
    localArtefacts(
      result,
      resolveSubset(result.resolution, all, optionalArtefacts).toList
    )

  /** Resolves path to JAR file of requested artefact */
  def artefactPath(
    result: ResolutionResult,
    artefact: model.Artefact,
    platform: Platform,
    platformVersion: MavenCentral.PlatformVersion,
    compilerVersion: MavenCentral.CompilerVersion,
    version: String
  ): Option[Path] = {
    val name =
      artefact.versionTag.fold(artefact.name)(
        vt =>
          MavenCentral.formatArtefactName(
            artefact.name,
            vt,
            platform,
            platformVersion,
            compilerVersion
          )
      )

    result.resolution
      .dependencyArtifacts()
      .find {
        case (dep, attr, art) =>
          dep.module.name.value == name && dep.version == version
      }
      .map(a => result.artefacts(a._3.url).toPath)
  }
}
