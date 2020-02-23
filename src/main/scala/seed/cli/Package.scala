package seed.cli

import java.io.FileOutputStream
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import seed.artefact.{ArtefactResolution, Coursier}
import seed.build.{BloopClient, Bsp}
import seed.cli.util.{Ansi, ConsoleOutput, RTS}
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.generation.util.PathUtil
import seed.model.Build.{JavaDep, Resolvers}
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
  ): Boolean = {
    Cli.showResolvers(seedConfig, resolvers, packageConfig, log)
    val tmpfs = packageConfig.tmpfs || seedConfig.build.tmpfs

    val buildPath  = PathUtil.buildPath(projectPath, tmpfs, log)
    val outputPath = output.getOrElse(buildPath.resolve("dist"))

    if (!build.contains(module)) {
      log.error(s"Module ${Ansi.italic(module)} does not exist")
      false
    } else {
      def onBuilt(
        consoleOutput: ConsoleOutput,
        modulePaths: Map[(String, Platform), String]
      ) = UIO {
        val paths = modulePaths.toList.map(_._2).map(Paths.get(_))
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
              libraryClassPath(
                seedConfig,
                packageConfig,
                resolvers,
                build,
                module,
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
            new FileOutputStream(outputJar.toFile),
            mainClass,
            classPath,
            log
          )
          log.info(s"Created JAR file ${Ansi.italic(outputJar.toString)}")
        }
      }

      val program = buildModule(
        log,
        projectPath,
        build,
        progress,
        List(module -> JVM),
        onBuilt
      )

      RTS.unsafeRunSync(program).succeeded
    }
  }

  def buildModule(
    log: Log,
    projectPath: Path,
    build: Build,
    progress: Boolean,
    modules: List[(String, Platform)],
    onBuilt: (ConsoleOutput, Map[(String, Platform), String]) => UIO[Unit]
  ): UIO[Unit] = {
    val expandedModules = BuildConfig.expandModules(build, modules)

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

          val program =
            for {
              moduleDirs <- classDirectories.option.map(_.get)
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
                _ <- {
                  consoleOutput.log.info("All modules compiled")
                  moduleDirs.foreach {
                    case (module, dir) =>
                      consoleOutput.log.debug(
                        s"Module path for ${seed.cli.util.Module
                          .format(module._1, module._2)}: ${Ansi.italic(dir)}"
                      )
                  }
                  onBuilt(consoleOutput, moduleDirs)
                }
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

  def libraryClassPath(
    seedConfig: Config,
    packageConfig: Cli.PackageConfig,
    resolvers: Resolvers,
    build: Build,
    moduleName: String,
    outputPath: Path,
    log: Log
  ): List[String] = {
    val resolved = ArtefactResolution.resolvePackageArtefacts(
      seedConfig,
      packageConfig,
      resolvers,
      build,
      moduleName,
      JVM,
      log
    )

    val cachePath = ArtefactResolution.cachePath(seedConfig, packageConfig)

    import resolved._
    val resolvedDeps =
      Coursier.localArtefacts(resolution, deps.toList).sortBy(_.libraryJar)

    log.info(
      s"Copying ${Ansi.bold(resolvedDeps.length.toString)} libraries to ${Ansi
        .italic(outputPath.toString)}..."
    )
    resolvedDeps.foreach { dep =>
      val target =
        outputPath.resolve(cachePath.relativize(dep.libraryJar))
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
    resolvedDeps.map(p => cachePath.relativize(p.libraryJar).toString)
  }
}
