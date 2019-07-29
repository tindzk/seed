package seed.cli

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import seed.artefact.{ArtefactResolution, Coursier}
import seed.cli.util.Ansi
import seed.config.BuildConfig
import seed.generation.util.PathUtil
import seed.model
import seed.model.Build.{JavaDep, Module}
import seed.model.{Build, Config}
import seed.model.Platform.JVM
import seed.{Cli, Log}

object Package {
  def ui(seedConfig: Config,
         projectPath: Path,
         build: model.Build,
         module: String,
         output: Option[Path],
         libs: Boolean,
         packageConfig: Cli.PackageConfig,
         log: Log
        ): Unit = {
    val tmpfs = packageConfig.tmpfs || seedConfig.build.tmpfs
    val buildPath = PathUtil.buildPath(projectPath, tmpfs, log)
    val bloopBuildPath = buildPath.resolve("bloop")
    val platform = JVM
    val outputPath = output.getOrElse(buildPath.resolve("dist"))

    build.module.get(module) match {
      case None => log.error(s"Module ${Ansi.italic(module)} does not exist")
      case Some(resolvedModule) =>
        val paths = (
          List(module) ++ BuildConfig.collectJvmModuleDeps(build, resolvedModule)
        ).map { name =>
          if (BuildConfig.isCrossBuild(build.module(name)))
            bloopBuildPath.resolve(name + "-" + platform.id)
          else
            bloopBuildPath.resolve(name)
        }

        val notFound = paths.find(p => !Files.exists(p))
        if (notFound.isDefined)
          log.error(s"${Ansi.italic(notFound.get.toString)} does not exist. Build the module with `bloop compile <module name>` first")
        else if (paths.isEmpty)
          log.error(s"No build paths were found")
        else {
          val files = collectFiles(paths)
          val jvmModule = resolvedModule.jvm.getOrElse(resolvedModule)

          val classPath =
            if (!libs) List()
            else getLibraryClassPath(
              seedConfig, packageConfig, build, jvmModule, outputPath, log)

          val mainClass = jvmModule.mainClass
          if (mainClass.isEmpty)
            log.warn(s"No main class was set in build file for module ${Ansi.italic(module)}")
          else
            log.info(s"Main class is ${Ansi.italic(mainClass.get)}")

          if (!Files.exists(outputPath)) Files.createDirectories(outputPath)
          val outputJar = outputPath.resolve(module + ".jar")
          Files.deleteIfExists(outputJar)
          seed.generation.Package.create(files, outputJar, mainClass, classPath, log)
      }
    }
  }

  def collectFiles(paths: List[Path]): List[(Path, String)] =
    paths.sorted.flatMap(path =>
      Files.list(path).iterator().asScala.toList
        .filter(_.getFileName.toString != "analysis.bin")
        .map(p => p -> path.relativize(p).toString))

  def getLibraryClassPath(seedConfig: Config,
                          packageConfig: Cli.PackageConfig,
                          build: Build,
                          jvmModule: Module,
                          outputPath: Path,
                          log: Log
                         ): List[String] = {
    val scalaVersion = BuildConfig.scalaVersion(build.project,
      List(jvmModule))
    val scalaLibraryDep =
      JavaDep(build.project.scalaOrganisation, "scala-library", scalaVersion)
    val scalaReflectDep =
      JavaDep(build.project.scalaOrganisation, "scala-reflect", scalaVersion)
    val platformDeps = Set(scalaLibraryDep, scalaReflectDep)

    val libraryDeps = ArtefactResolution.allLibraryDeps(build,
      Set(JVM))
    val (resolvedDepPath, libraryResolution, platformResolution) =
      ArtefactResolution.resolution(seedConfig, build,
        packageConfig, optionalArtefacts = false, libraryDeps,
        List(platformDeps), log)
    val resolvedLibraryDeps = Coursier.localArtefacts(
      libraryResolution, libraryDeps, optionalArtefacts = false)

    val resolvedPlatformDeps = Coursier.localArtefacts(
      platformResolution.head, platformDeps, optionalArtefacts = false)

    val resolvedDeps = (resolvedLibraryDeps ++ resolvedPlatformDeps)
      .distinct.sortBy(_.libraryJar)

    log.info(s"Copying ${Ansi.bold(resolvedDeps.length.toString)} libraries to ${Ansi.italic(outputPath.toString)}...")
    resolvedDeps.foreach { dep =>
      val target = outputPath.resolve(resolvedDepPath.relativize(dep.libraryJar))
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
