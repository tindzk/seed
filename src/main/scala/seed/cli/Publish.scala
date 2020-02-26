package seed.cli

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path, Paths}

import org.apache.commons.io.FileUtils
import seed.artefact.ArtefactResolution.{
  CompilerResolution,
  ModuleRef,
  RuntimeResolution
}
import seed.artefact.{ArtefactResolution, Coursier, SemanticVersioning}
import seed.cli.util.{
  Ansi,
  ConsoleOutput,
  Module,
  ProgressBar,
  ProgressBarItem,
  ProgressBars,
  RTS
}
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.generation.util.PathUtil
import seed.model.Build.{JavaDep, Resolvers}
import seed.model.Platform.{JavaScript, Native}
import seed.model.{Config, Platform}
import seed.publish.Bintray
import seed.publish.util.Http
import seed.{Cli, Log}
import zio._

import scala.util.Try

object Publish {
  def ui(
    seedConfig: Config,
    projectPath: Path,
    resolvers: Resolvers,
    `package`: seed.model.Build.Package,
    build: Build,
    version: Option[String],
    target: String,
    modules: List[String],
    progress: Boolean,
    skipSources: Boolean,
    skipDocs: Boolean,
    packageConfig: Cli.PackageConfig,
    log: Log
  ): Unit = {
    Cli.showResolvers(seedConfig, resolvers, packageConfig, log)

    val bintrayUser = Some(seedConfig.repository.bintray.user)
      .filter(_.nonEmpty)
      .orElse(sys.env.get("BINTRAY_USER").filter(_.nonEmpty))
    val bintrayApiKey = Some(seedConfig.repository.bintray.apiKey)
      .filter(_.nonEmpty)
      .orElse(sys.env.get("BINTRAY_API_KEY").filter(_.nonEmpty))

    if (bintrayUser.isEmpty || bintrayApiKey.isEmpty) {
      log.error("Bintray username and API key must be set")
      sys.exit(1)
    } else if (!`package`.name.exists(_.nonEmpty) ||
               !`package`.organisation.exists(_.nonEmpty)) {
      log.error("Package name and organisation must be set in build file")
      sys.exit(1)
    }

    if (!target.startsWith("bintray:") || target.count(_ == '/') != 2) {
      log.error("Seed can only publish to Bintray")
      log.error(
        s"${Ansi.bold("Syntax:")} ${Ansi.italic("seed publish bintray:<organisation>/<repository>/<package> <module>")}"
      )
      sys.exit(1)
    }

    val bintrayPath = target.drop("bintray:".length).split('/')
    val (bintrayOrganisation, bintrayRepository, bintrayPackage) =
      (bintrayPath(0), bintrayPath(1), bintrayPath(2))

    val tmpfs = packageConfig.tmpfs || seedConfig.build.tmpfs

    val buildPath = PathUtil.buildPath(projectPath, tmpfs, log)

    val parsedModules = modules.map(util.Target.parseModuleString(build))
    util.Validation.unpack(parsedModules) match {
      case Left(errors) =>
        errors.foreach(log.error(_))
        sys.exit(1)

      case Right(allModules) =>
        val publishModules = allModules.flatMap {
          case util.Target.Parsed(module, None) =>
            BuildConfig.allTargets(build, module.name)
          case util.Target.Parsed(module, Some(Left(platform))) =>
            List((module.name, platform))
          case util.Target.Parsed(_, Some(Right(_))) => List()
        }

        val v = getVersion(projectPath, version, log).getOrElse(sys.exit(1))
        log.info(s"Publishing version $v...")

        def onBuilt(
          consoleOutput: ConsoleOutput,
          modulePaths: Map[(String, Platform), String]
        ): UIO[Unit] = {
          val log = consoleOutput.log

          val paths = modulePaths.mapValues(Paths.get(_))
          require(paths.values.forall(Files.exists(_)))

          if (paths.isEmpty) {
            log.error("No build paths were found")
            UIO.interrupt
          } else {
            consoleOutput.log.info(
              s"Publishing ${Ansi.bold(publishModules.length.toString)} modules..."
            )

            consoleOutput.reset()
            val pb = new ProgressBars(
              ConsoleOutput.conditional(progress, consoleOutput),
              publishModules.map {
                case (m, p) =>
                  ProgressBarItem(
                    BuildConfig.targetName(build, m, p),
                    Module.format(m, p)
                  )
              }
            )

            val runtimeLibs = ArtefactResolution.allRuntimeLibs(build)
            val runtimeResolution = ArtefactResolution.runtimeResolution(
              build,
              seedConfig,
              resolvers,
              packageConfig,
              false,
              log
            )
            val compilerResolution = ArtefactResolution.compilerResolution(
              build,
              seedConfig,
              resolvers,
              packageConfig,
              false,
              log
            )

            val p = UIO(
              Http.create(
                log,
                "bintray.com",
                (bintrayUser.get, bintrayApiKey.get)
              )
            ).bracket(http => UIO(http.destroy())) { http =>
              UIO
                .collectAll(publishModules.map {
                  case (module, platform) =>
                    publishModule(
                      runtimeLibs,
                      http,
                      bintrayOrganisation,
                      bintrayRepository,
                      bintrayPackage,
                      v,
                      seedConfig,
                      runtimeResolution,
                      compilerResolution,
                      resolvers,
                      `package`,
                      packageConfig,
                      pb,
                      paths,
                      modulePaths,
                      buildPath,
                      build,
                      module,
                      platform,
                      skipSources,
                      skipDocs,
                      log
                    )
                })
                .flatMap { _ =>
                  log.info("Publishing content...")
                  Bintray.publishContent(
                    http,
                    log,
                    organisation = bintrayOrganisation,
                    repository = bintrayRepository,
                    `package` = bintrayPackage,
                    packageVersion = v
                  )
                }
            }

            if (progress)
              ProgressBars.withProgressBar(
                pb,
                consoleOutput,
                p.option.map(_.isDefined)
              )
            else
              p.option.flatMap(r => if (r.isDefined) IO.unit else IO.interrupt)
          }
        }

        val program = Package.buildModule(
          log,
          projectPath,
          build,
          progress,
          publishModules,
          onBuilt
        )

        val result = RTS.unsafeRunSync(program)
        if (!result.succeeded) log.error("Publishing failed")
        sys.exit(if (result.succeeded) 0 else 1)
    }
  }

  def publish(
    bintrayOrganisation: String,
    bintrayRepository: String,
    bintrayPackage: String,
    http: Http,
    log: Log,
    `package`: seed.model.Build.Package,
    classesJar: UIO[ByteArrayOutputStream],
    docsJar: Option[UIO[ByteArrayOutputStream]],
    sourcesJar: Option[UIO[ByteArrayOutputStream]],
    pom: UIO[String],
    version: String,
    packageArtefact: String,
    pbUpdate: (Int, Int) => Unit
  ): UIO[Unit] = {
    val uploadJar = classesJar.flatMap(
      classesJar =>
        Bintray.uploadMavenFile(
          http,
          log,
          organisation = bintrayOrganisation,
          repository = bintrayRepository,
          `package` = bintrayPackage,
          packageGroup = `package`.organisation.get,
          packageArtefact = packageArtefact,
          packageClassifier = "",
          packageVersion = version,
          bytes = classesJar.toByteArray,
          extension = "jar"
        )
    )

    val uploadDocs = docsJar.toList.map(
      _.flatMap(
        jar =>
          Bintray.uploadMavenFile(
            http,
            log,
            organisation = bintrayOrganisation,
            repository = bintrayRepository,
            `package` = bintrayPackage,
            packageGroup = `package`.organisation.get,
            packageArtefact = packageArtefact,
            packageClassifier = "-javadoc",
            packageVersion = version,
            bytes = jar.toByteArray,
            extension = "jar"
          )
      )
    )

    val uploadSources = sourcesJar.toList.map(
      _.flatMap(
        jar =>
          Bintray.uploadMavenFile(
            http,
            log,
            organisation = bintrayOrganisation,
            repository = bintrayRepository,
            `package` = bintrayPackage,
            packageGroup = `package`.organisation.get,
            packageArtefact = packageArtefact,
            packageClassifier = "-sources",
            packageVersion = version,
            bytes = jar.toByteArray,
            extension = "jar"
          )
      )
    )

    val uploadPom = pom.flatMap(
      pom =>
        Bintray.uploadMavenFile(
          http,
          log,
          organisation = bintrayOrganisation,
          repository = bintrayRepository,
          `package` = bintrayPackage,
          packageGroup = `package`.organisation.get,
          packageArtefact = packageArtefact,
          packageClassifier = "",
          packageVersion = version,
          bytes = pom.getBytes,
          extension = "pom"
        )
    )

    var completed = 0
    val all       = List(uploadJar, uploadPom) ++ uploadDocs ++ uploadSources

    def onComplete(uio: UIO[Unit]): UIO[Unit] = uio.map { _ =>
      completed += 1
      pbUpdate(completed, all.length)
    }

    for {
      // Change progress bar to 'in progress'
      _ <- UIO(pbUpdate(0, all.length))
      _ <- UIO.collectAll(all.map(onComplete))
    } yield ()
  }

  def createPom(
    pkg: seed.model.Build.Package,
    artefactId: String,
    version: String,
    dependencies: Set[JavaDep]
  ): String = {
    import pine._

    val url = pkg.url.map(url => xml"""<url>$url</url>""").toList

    val licences =
      pkg.licences.map { licence =>
        xml"""
          <license>
            <name>${licence.name}</name>
            <url>${licence.url}</url>
            <distribution>repo</distribution>
          </license>
        """
      }

    val developers = pkg.developers.map { developer =>
      xml"""
        <developer>
          <id>${developer.id}</id>
          <name>${developer.name}</name>
          <email>${developer.email}</email>
        </developer>
      """
    }

    val scm = pkg.scm
      .map(
        scm =>
          xml"""
            <scm>
              <url>${scm.url}</url>
              <connection>${scm.connection}</connection>
              <developerConnection>${scm.developerConnection
            .getOrElse(scm.connection)}</developerConnection>
            </scm>
          """
      )
      .toList

    val dependenciesXml = dependencies.toList
      .sortBy(dep => (dep.organisation, dep.artefact))
      .map(dep => xml"""
        <dependency>
          <groupId>${dep.organisation}</groupId>
          <artifactId>${dep.artefact}</artifactId>
          <version>${dep.version}</version>
        </dependency>
      """)

    xml"""
      <?xml version='1.0' encoding='UTF-8'?>
      <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>${pkg.organisation.get}</groupId>
          <artifactId>$artefactId</artifactId>
          <packaging>bundle</packaging>
          <description>${pkg.name.get}</description>
          <version>$version</version>
          <name>${pkg.name.get}</name>
          $url
          <organization>
            <name>${pkg.organisation.get}</name>
          </organization>
          <licenses>$licences</licenses>
          <developers>$developers</developers>
          <dependencies>$dependenciesXml</dependencies>
          $scm
      </project>
    """.toXml
  }

  def encodeVersion(version: String): String =
    if (SemanticVersioning.isPreRelease(version)) version
    else SemanticVersioning.majorMinorVersion(version)

  def getVersion(
    projectPath: Path,
    version: Option[String],
    log: Log
  ): Option[String] =
    version
      .orElse {
        log.info("Determining version from Git tag...")
        import sys.process._
        val result =
          Try(Process("git describe --tags", projectPath.toFile).!!).toOption
            .map { t =>
              val tag = t.trim
              if (!tag.startsWith("v")) tag else tag.tail
            }
        if (result.isEmpty) log.error("Could not retrieve Git tag")
        result
      }
      .flatMap { v =>
        val parsed = SemanticVersioning.parseVersion(v)
        if (parsed.isDefined) Some(v)
        else {
          log.error(s"'$v' is not a valid semantic version")
          None
        }
      }

  def publishModule(
    deps: Map[ModuleRef, Set[JavaDep]],
    http: Http,
    bintrayOrganisation: String,
    bintrayRepository: String,
    bintrayPackage: String,
    v: String,
    seedConfig: Config,
    resolution: Map[ModuleRef, Coursier.ResolutionResult],
    compilerResolution: CompilerResolution,
    resolvers: Resolvers,
    `package`: seed.model.Build.Package,
    packageConfig: Cli.PackageConfig,
    pb: ProgressBars,
    paths: Map[(String, Platform), Path],
    modulePaths: Map[(String, Platform), String],
    buildPath: Path,
    build: Build,
    module: String,
    platform: Platform,
    skipSources: Boolean,
    skipDocs: Boolean,
    log: Log
  ): ZIO[Any, Nothing, Unit] = {
    val platformModule =
      BuildConfig.platformModule(build(module).module, platform).get

    val classesJar =
      packageClasses(build, module, platform, platformModule, paths, log)

    val docsJar =
      if (!`package`.docs || skipDocs) None
      else
        Some(
          packageDocs(
            deps,
            seedConfig,
            resolution,
            compilerResolution,
            resolvers,
            packageConfig,
            modulePaths,
            buildPath,
            build,
            module,
            platform,
            log
          )
        )

    val sourcesJar =
      if (!`package`.sources || skipSources) None
      else Some(packageSources(build, module, platform, log))

    val packageArtefact = packageArtefactName(module, platform, platformModule)

    val pom =
      resolveArtefactsAndCreatePom(
        seedConfig,
        resolvers,
        `package`,
        build,
        module,
        platform,
        packageArtefact,
        v,
        packageConfig,
        log
      )

    publish(
      bintrayOrganisation,
      bintrayRepository,
      bintrayPackage,
      http,
      log,
      `package`,
      classesJar,
      docsJar,
      sourcesJar,
      pom,
      v,
      packageArtefact,
      (current, total) =>
        pb.update(
          BuildConfig.targetName(build, module, platform),
          _.copy(
            step = 100 * current / total,
            total = 100,
            result = ProgressBar.Result.InProgress
          )
        )
    ).map(
        _ =>
          pb.update(
            BuildConfig.targetName(build, module, platform),
            _.copy(result = ProgressBar.Result.Success)
          )
      )
      .onTermination(
        _ =>
          UIO(
            pb.update(
              BuildConfig.targetName(build, module, platform),
              _.copy(result = ProgressBar.Result.Failure)
            )
          )
      )
  }

  def packageClasses(
    build: Build,
    module: String,
    platform: Platform,
    platformModule: seed.model.Build.Module,
    paths: Map[(String, Platform), Path],
    log: Log
  ): UIO[ByteArrayOutputStream] = UIO {
    val files     = Package.collectFiles(List(paths((module, platform))))
    val mainClass = platformModule.mainClass
    if (mainClass.isDefined)
      log.info(s"Main class is ${Ansi.italic(mainClass.get)}")

    val classesJar = new ByteArrayOutputStream(1024)
    seed.generation.Package.create(files, classesJar, mainClass, List(), log)
    classesJar
  }

  def packageDocs(
    deps: Map[ModuleRef, Set[JavaDep]],
    seedConfig: Config,
    runtimeResolution: RuntimeResolution,
    compilerResolution: CompilerResolution,
    resolvers: Resolvers,
    packageConfig: Cli.PackageConfig,
    modulePaths: Map[(String, Platform), String],
    buildPath: Path,
    build: Build,
    module: String,
    platform: Platform,
    log: Log
  ): UIO[ByteArrayOutputStream] =
    UIO {

      /** Same as in [[Doc.ui]] */
      val outputDocs =
        buildPath.resolve("docs").resolve(module + "-" + platform.id)
      FileUtils.deleteDirectory(outputDocs.toFile)
      Files.createDirectories(outputDocs)
      log.info(
        s"Creating documentation for module ${Ansi.italic(module)} in $outputDocs..."
      )

      if (!seed.cli.Doc.documentModule(
            build,
            resolvers,
            runtimeResolution,
            compilerResolution,
            seedConfig,
            packageConfig,
            module,
            platform,
            deps,
            outputDocs.toFile,
            modulePaths,
            log
          )) sys.exit(1)

      val stream = new ByteArrayOutputStream(1024)
      seed.generation.Package.create(
        Package.collectFiles(List(outputDocs)),
        stream,
        None,
        List(),
        log
      )
      stream
    }

  def packageSources(
    build: Build,
    module: String,
    platform: Platform,
    log: Log
  ): UIO[ByteArrayOutputStream] =
    UIO {
      val stream = new ByteArrayOutputStream(1024)
      val sourcePaths = Package.collectFiles(
        BuildConfig.sourcePaths(build, List((module, platform)))
      )
      seed.generation.Package.create(sourcePaths, stream, None, List(), log)
      stream
    }

  def packageArtefactName(
    module: String,
    platform: Platform,
    platformModule: seed.model.Build.Module
  ): String = {
    val packageArtefactPlatform =
      if (platform == JavaScript)
        "_sjs" + encodeVersion(platformModule.scalaJsVersion.get)
      else if (platform == Native)
        "_native" + encodeVersion(platformModule.scalaNativeVersion.get)
      else ""
    val scalaVersion = "_" + encodeVersion(platformModule.scalaVersion.get)
    s"$module$packageArtefactPlatform$scalaVersion"
  }

  def resolveArtefactsAndCreatePom(
    seedConfig: Config,
    resolvers: Resolvers,
    `package`: seed.model.Build.Package,
    build: Build,
    module: String,
    platform: Platform,
    packageArtefact: String,
    v: String,
    packageConfig: Cli.PackageConfig,
    log: Log
  ): UIO[String] = UIO {
    val resolution = ArtefactResolution.resolvePackageArtefacts(
      seedConfig,
      packageConfig,
      resolvers,
      build,
      module,
      platform,
      log
    )
    createPom(`package`, packageArtefact, v, resolution.deps.keySet)
  }
}
