package seed.cli

import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import seed.artefact.{ArtefactResolution, Coursier}
import seed.cli.util.Ansi
import seed.config.BuildConfig
import seed.generation.Bloop
import seed.model
import seed.model.Build.Dep
import seed.model.Config
import seed.model.Platform.JVM
import seed.{Cli, Log}

object Package {
  def ui(seedConfig: Config,
         projectPath: Path,
         build: model.Build,
         module: String,
         output: Option[Path],
         libs: Boolean,
         packageConfig: Cli.PackageConfig
        ): Unit = {
    val tmpfs = packageConfig.tmpfs || seedConfig.build.tmpfs
    val buildPath = Bloop.getBuildPath(projectPath, tmpfs)
    val platform = JVM
    val outputPath = output.getOrElse(Paths.get("dist"))

    build.module.get(module) match {
      case None => Log.error(s"Module ${Ansi.italic(module)} does not exist")
      case Some(resolvedModule) =>
        val paths = (
          List(module) ++ BuildConfig.collectJvmModuleDeps(build, resolvedModule)
        ).map { name =>
          if (BuildConfig.isCrossBuild(build.module(name)))
            buildPath.resolve(name + "-" + platform.id)
          else
            buildPath.resolve(name)
        }

        val notFound = paths.find(p => !Files.exists(p))
        if (notFound.isDefined)
          Log.error(s"${Ansi.italic(notFound.get.toString)} does not exist. Build the module with `bloop compile <module name>` first")
        else if (paths.isEmpty)
          Log.error(s"No build paths were found")
        else {
          val files = paths.sorted.flatMap(path =>
            Files.list(path).iterator().asScala.toList
              .filter(_.getFileName.toString != "analysis.bin")
              .map(p => p -> path.relativize(p).toString))

          val jvmModule = resolvedModule.jvm.getOrElse(resolvedModule)

          val mainClass = jvmModule.mainClass
          if (mainClass.isEmpty)
            Log.warn(s"No main class was set in build file for module ${Ansi.italic(module)}")
          else
            Log.info(s"Main class is ${Ansi.italic(mainClass.get)}")

          val classPath =
            if (!libs) List()
            else {
              val scalaVersion = BuildConfig.scalaVersion(build.project,
                List(jvmModule))
              val scalaLibraryDep =
                Dep(build.project.scalaOrganisation, "scala-library", scalaVersion)
              val scalaReflectDep =
                Dep(build.project.scalaOrganisation, "scala-reflect", scalaVersion)
              val platformDeps = Set(scalaLibraryDep, scalaReflectDep)

              val libraryDeps = ArtefactResolution.allLibraryDeps(build,
                Set(JVM))
              val (resolvedDepPath, libraryResolution, platformResolution) =
                ArtefactResolution.resolution(seedConfig, build,
                  packageConfig, optionalArtefacts = false, libraryDeps,
                  List(platformDeps))
              val resolvedLibraryDeps = Coursier.localArtefacts(
                libraryResolution, libraryDeps)

              val resolvedPlatformDeps = Coursier.localArtefacts(
                platformResolution.head, platformDeps)

              val resolvedDeps = (resolvedLibraryDeps ++ resolvedPlatformDeps)
                .distinct.sortBy(_.libraryJar)

              Log.info(s"Copying ${Ansi.bold(resolvedDeps.length.toString)} libraries to ${Ansi.italic(outputPath.toString)}...")
              resolvedDeps.foreach { dep =>
                val target = outputPath.resolve(resolvedDepPath.relativize(dep.libraryJar))
                if (Files.exists(target))
                  Log.debug(s"Skipping ${dep.libraryJar.toString} as it exists already.")
                else {
                  Log.debug(s"Copying ${dep.libraryJar.toString}...")

                  if (!Files.exists(target.getParent))
                    Files.createDirectories(target.getParent)
                  Files.copy(dep.libraryJar, target)
                }
              }

              Log.info(s"Adding libraries to class path...")
              resolvedDeps.map(p => resolvedDepPath.relativize(p.libraryJar).toString)
            }

          if (!Files.exists(outputPath)) Files.createDirectories(outputPath)
          val outputJar = outputPath.resolve(module + ".jar")
          Files.deleteIfExists(outputJar)
          seed.generation.Package.create(files, outputJar, mainClass, classPath)
      }
    }
  }
}
