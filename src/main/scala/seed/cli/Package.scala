package seed.cli

import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import seed.artefact.{ArtefactResolution, Coursier}
import seed.build.{BloopClient, Bsp}
import seed.cli.util.{Ansi, ConsoleOutput, RTS}
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.generation.util.PathUtil
import seed.model.Build.{JavaDep, Module, Resolvers}
import seed.model.{Config, Platform}
import seed.model.Platform.JVM
import seed.{Cli, Log}
import zio._

object Package {
  def ui(
    seedConfig: Config,
    projectPath: Path,
    resolvers: Resolvers,
    build: Build,
    module: String,
    output: Option[Path],
    libs: Boolean,
    progress: Boolean,
    packageConfig: Cli.PackageConfig,
    log: Log
  ): Unit = {
    val tmpfs = packageConfig.tmpfs || seedConfig.build.tmpfs

    val buildPath  = PathUtil.buildPath(projectPath, tmpfs, log)
    val outputPath = output.getOrElse(buildPath.resolve("dist"))

    if (!build.contains(module))
      log.error(s"Module ${Ansi.italic(module)} does not exist")
    else {
      val expandedModules =
        BuildConfig.expandModules(build, List(module -> JVM))

      def onBuilt(stringPaths: List[String]) = UIO {
        val paths = stringPaths.map(Paths.get(_))
        require(paths.forall(Files.exists(_)))

        if (paths.isEmpty) log.error("No build paths were found")
        else {
          val files = collectFiles(paths)

          val resolvedModule = build(module)
          val jvmModule =
            resolvedModule.module.jvm.getOrElse(resolvedModule.module)

          val classPath =
            if (!libs) List()
            else
              getLibraryClassPath(
                seedConfig,
                packageConfig,
                resolvers,
                build,
                jvmModule,
                outputPath,
                log
              )

          val mainClass = jvmModule.mainClass
          if (mainClass.isEmpty)
            log.warn(
              s"No main class was set in build file for module ${Ansi.italic(module)}"
            )
          else
            log.info(s"Main class is ${Ansi.italic(mainClass.get)}")

          if (!Files.exists(outputPath)) Files.createDirectories(outputPath)
          val outputJar = outputPath.resolve(module + ".jar")
          Files.deleteIfExists(outputJar)
          seed.generation.Package.create(
            files,
            outputJar,
            mainClass,
            classPath,
            log
          )
        }
      }

      val program =
        buildModule(log, projectPath, build, progress, expandedModules, onBuilt)
      RTS.unsafeRunSync(program)
    }
  }

  def buildModule(
    log: Log,
    projectPath: Path,
    build: Build,
    progress: Boolean,
    expandedModules: List[(String, Platform)],
    onBuilt: List[String] => UIO[Unit]
  ): UIO[Unit] = {
    val consoleOutput = new ConsoleOutput(log, print)

    val client = new BloopClient(
      consoleOutput,
      progress,
      projectPath,
      build,
      expandedModules,
      _ => ()
    )

    Bsp
      .runBspServerAndConnect(client, projectPath, consoleOutput.log)
      .flatMap {
        case (bspProcess, socket, server) =>
          val classDirectories = Bsp.classDirectories(
            server,
            build,
            projectPath,
            expandedModules
          )

          consoleOutput.log.info(
            s"Compiling ${Ansi.bold(expandedModules.length.toString)} modules..."
          )

          val program =
            for {
              moduleDirs <- classDirectories.option.map(_.get)
              _ = {
                moduleDirs.foreach {
                  case (module, dir) =>
                    consoleOutput.log.debug(
                      s"Module ${Ansi.italic(module._1)}: ${Ansi.italic(dir)}"
                    )
                }
              }
              _ <- (for {
                _ <- Bsp.compile(
                  client,
                  server,
                  consoleOutput,
                  progress,
                  build,
                  projectPath,
                  expandedModules
                )
                _ <- onBuilt(moduleDirs.toList.map(_._2))
              } yield ()).ensuring(Bsp.shutdown(bspProcess, socket, server))
            } yield ()

          Bsp.interruptIfParentFails(bspProcess.fiber.join, program)
      }
  }

  def collectFiles(paths: List[Path]): List[(Path, String)] =
    paths.sorted.flatMap(
      path =>
        Files
          .list(path)
          .iterator()
          .asScala
          .toList
          .filter(_.getFileName.toString != "analysis.bin")
          .map(p => p -> path.relativize(p).toString)
    )

  def getLibraryClassPath(
    seedConfig: Config,
    packageConfig: Cli.PackageConfig,
    resolvers: Resolvers,
    build: Build,
    jvmModule: Module,
    outputPath: Path,
    log: Log
  ): List[String] = {
    val scalaVersion = jvmModule.scalaVersion.get
    val scalaLibraryDep =
      JavaDep(jvmModule.scalaOrganisation.get, "scala-library", scalaVersion)
    val scalaReflectDep =
      JavaDep(jvmModule.scalaOrganisation.get, "scala-reflect", scalaVersion)
    val platformDeps = Set(scalaLibraryDep, scalaReflectDep)

    val libraryDeps = ArtefactResolution.allLibraryDeps(build, Set(JVM))
    val (resolvedDepPath, libraryResolution, platformResolution) =
      ArtefactResolution.resolution(
        seedConfig,
        resolvers,
        build,
        packageConfig,
        optionalArtefacts = false,
        libraryDeps,
        List(platformDeps),
        log
      )
    val resolvedLibraryDeps = Coursier.localArtefacts(
      libraryResolution,
      libraryDeps,
      optionalArtefacts = false
    )

    val resolvedPlatformDeps = Coursier.localArtefacts(
      platformResolution.head,
      platformDeps,
      optionalArtefacts = false
    )

    val resolvedDeps = (resolvedLibraryDeps ++ resolvedPlatformDeps).distinct
      .sortBy(_.libraryJar)

    log.info(
      s"Copying ${Ansi.bold(resolvedDeps.length.toString)} libraries to ${Ansi
        .italic(outputPath.toString)}..."
    )
    resolvedDeps.foreach { dep =>
      val target =
        outputPath.resolve(resolvedDepPath.relativize(dep.libraryJar))
      if (Files.exists(target))
        log.debug(s"Skipping ${dep.libraryJar.toString} as it exists already")
      else {
        log.debug(s"Copying ${dep.libraryJar.toString}...")

        if (!Files.exists(target.getParent))
          Files.createDirectories(target.getParent)
        Files.copy(dep.libraryJar, target)
      }
    }

    log.info(s"Adding libraries to class path...")
    resolvedDeps.map(p => resolvedDepPath.relativize(p.libraryJar).toString)
  }
}
