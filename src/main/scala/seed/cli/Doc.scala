package seed.cli

import java.io.File
import java.nio.file.{Files, Path}
import java.util.function.Consumer

import org.apache.commons.io.FileUtils
import seed.{BuildInfo, Cli, Log}
import seed.model.{Config, Platform}
import seed.Cli.{Command, PackageConfig}
import seed.artefact.ArtefactResolution.{
  CompilerResolution,
  ModuleRef,
  RuntimeResolution
}
import seed.artefact.{ArtefactResolution, Coursier, SemanticVersioning}
import seed.cli.util.{Ansi, ConsoleOutput}
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.generation.util.{PathUtil, ScalaCompiler}
import seed.model.Build.{JavaDep, Resolvers}
import zio.UIO

import scala.util.Try

object Doc {
  def ui(
    build: BuildConfig.Result,
    bloopPath: Path,
    seedConfig: Config,
    command: Command.Doc,
    packageConfig: Cli.PackageConfig,
    progress: Boolean,
    log: Log
  ): UIO[Unit] = {
    Cli.showResolvers(seedConfig, build.resolvers, packageConfig, log)

    val tmpfs = packageConfig.tmpfs || seedConfig.build.tmpfs

    val buildPath   = PathUtil.buildPath(build.projectPath, tmpfs, log)
    val destination = command.output.getOrElse(buildPath.resolve("docs"))

    val parsedModules =
      command.modules.map(util.Target.parseModuleString(build.build))
    util.Validation.unpack(parsedModules) match {
      case Left(errors) =>
        errors.foreach(log.error(_))
        UIO.interrupt

      case Right(allModules) =>
        val docModules = allModules.flatMap {
          case util.Target.Parsed(module, None) =>
            BuildConfig.allTargets(build.build, module.name)
          case util.Target.Parsed(module, Some(Left(platform))) =>
            List((module.name, platform))
          case util.Target.Parsed(_, Some(Right(_))) => List()
        }

        val runtimeLibs = ArtefactResolution.allRuntimeLibs(build.build)
        val runtimeResolution = ArtefactResolution.runtimeResolution(
          build.build,
          seedConfig,
          build.resolvers,
          packageConfig,
          false,
          log
        )
        val compilerResolution = ArtefactResolution.compilerResolution(
          build.build,
          seedConfig,
          build.resolvers,
          packageConfig,
          false,
          log
        )

        def onBuilt(
          co: ConsoleOutput,
          paths: Map[(String, Platform), String]
        ): UIO[Unit] = {
          val r = docModules.forall {
            case (module, platform) =>
              if (!build.build.contains(module)) {
                log.error(s"Module ${Ansi.italic(module)} does not exist")
                false
              } else {
                log.info(s"Documenting module ${Ansi
                  .italic(seed.cli.util.Module.format(module, platform))}...")

                val moduleDest =
                  destination.resolve(module + "-" + platform.id)
                FileUtils.deleteDirectory(moduleDest.toFile)
                Files.createDirectories(moduleDest)

                log.info(
                  s"Output path: ${Ansi.italic(moduleDest.toString)}"
                )
                Try {
                  documentModule(
                    build.build,
                    build.resolvers,
                    runtimeResolution,
                    compilerResolution,
                    seedConfig,
                    command.packageConfig,
                    module,
                    platform,
                    runtimeLibs,
                    moduleDest.toFile,
                    paths,
                    log
                  )
                }.toEither match {
                  case Left(e) =>
                    log.error("Internal error occurred")
                    e.printStackTrace()
                    false
                  case Right(v) => v
                }
              }
          }

          if (r) UIO.succeed(()) else UIO.interrupt
        }

        Package.buildModule(
          log,
          bloopPath,
          build.build,
          progress,
          docModules,
          onBuilt
        )
    }
  }

  def documentModule(
    build: Build,
    resolvers: Resolvers,
    runtimeResolution: RuntimeResolution,
    compilerResolution: CompilerResolution,
    seedConfig: Config,
    packageConfig: PackageConfig,
    module: String,
    platform: Platform,
    runtimeLibs: Map[ModuleRef, Set[JavaDep]],
    dest: File,
    modulePaths: Map[(String, Platform), String],
    log: Log
  ): Boolean = {
    val deps =
      BuildConfig.collectModuleDeps(build, build(module).module, platform)
    val moduleClasspaths = deps.flatMap(d => modulePaths.get((d, platform)))

    val scaladocLibs = resolveScaladocBridge(
      build,
      module,
      platform,
      resolvers,
      packageConfig,
      seedConfig,
      log
    )

    val r = runtimeResolution((module, platform, ArtefactResolution.Regular))
    val resolvedLibraryDeps = Coursier
      .resolveSubset(
        r.resolution,
        runtimeLibs((module, platform, ArtefactResolution.Regular)),
        optionalArtefacts = false
      )
      .toList

    val moduleLibs =
      Coursier.localArtefacts(r, resolvedLibraryDeps).map(_.libraryJar)

    val classLoader = new java.net.URLClassLoader(
      scaladocLibs.map(_.toUri.toURL).toArray,
      null
    )

    val scaladocClass =
      classLoader.loadClass("seed.publish.util.Scaladoc")
    val scaladoc        = scaladocClass.newInstance()
    val scaladocMethods = scaladocClass.getDeclaredMethods.toList
    val scaladocExecuteMethod =
      scaladocMethods.find(_.getName == "execute").get
    val scaladocSettingsMethod =
      scaladocMethods.find(_.getName == "settings").get

    val sourceFiles = BuildConfig
      .sourcePaths(build, List(module -> platform))
      .flatMap(BuildConfig.allSourceFiles)
      .map(_.toFile)

    log.info(
      s"Documenting ${Ansi.bold(sourceFiles.length.toString)} files..."
    )

    val platformModule =
      BuildConfig.platformModule(build(module).module, platform).get
    val scalacParams = platformModule.scalaOptions ++ ScalaCompiler
      .compilerPlugIns(
        build,
        platformModule,
        compilerResolution,
        platform,
        platformModule.scalaVersion.get
      )

    val classpath = moduleClasspaths.map(new File(_)) ++
      moduleLibs.map(_.toFile)

    val settings = scaladocSettingsMethod.invoke(
      scaladoc,
      Array[File](),
      classpath.toArray,
      Array[File](),
      Array[File](),
      dest,
      scalacParams.mkString(" ")
    )

    val result = scaladocExecuteMethod
      .invoke(
        scaladoc,
        settings,
        sourceFiles.toArray,
        (message => log.error(Ansi.bold("[scaladoc] ") + message)): Consumer[
          String
        ],
        (message => log.warn(Ansi.bold("[scaladoc] ") + message)): Consumer[
          String
        ]
      )
      .asInstanceOf[java.lang.Boolean]

    if (result)
      log.info(
        s"Module ${Ansi.italic(seed.cli.util.Module.format(module, platform))} documented"
      )
    else
      log.error(
        s"Module ${Ansi.italic(seed.cli.util.Module.format(module, platform))} could not be documented"
      )

    result
  }

  def resolveScaladocBridge(
    build: Build,
    moduleName: String,
    platform: Platform,
    resolvers: Resolvers,
    packageConfig: PackageConfig,
    seedConfig: Config,
    log: Log
  ): List[Path] = {
    val module = build(moduleName).module
    val pm     = BuildConfig.platformModule(module, platform).get

    val scalaOrg = pm.scalaOrganisation.get
    val scalaVer = pm.scalaVersion.get

    val scalaCompilerDep = JavaDep(scalaOrg, "scala-compiler", scalaVer)
    val scalaLibraryDep  = JavaDep(scalaOrg, "scala-library", scalaVer)

    val scaladoc = JavaDep(
      BuildInfo.Organisation,
      "seed-scaladoc_" + SemanticVersioning.majorMinorVersion(scalaVer),
      BuildInfo.Version
    )

    val scaladocDeps = Set[JavaDep](scaladoc, scalaLibraryDep, scalaCompilerDep)

    val r =
      ArtefactResolution.resolution(
        seedConfig,
        resolvers,
        packageConfig,
        scaladocDeps,
        (scalaOrg, scalaVer),
        optionalArtefacts = false,
        log
      )

    val resolvedDeps = Coursier
      .resolveSubset(r.resolution, scaladocDeps, optionalArtefacts = false)
      .toList

    Coursier.localArtefacts(r, resolvedDeps).map(_.libraryJar)
  }
}
