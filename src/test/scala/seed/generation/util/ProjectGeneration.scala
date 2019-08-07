package seed.generation.util

import java.nio.file.{Files, Path, Paths}

import org.apache.commons.io.FileUtils
import seed.Log
import seed.artefact.{ArtefactResolution, Coursier}
import seed.config.BuildConfig
import seed.config.BuildConfig.{Build, ModuleConfig}
import seed.generation.Bloop
import seed.model.Build.{JavaDep, Resolvers}
import seed.model.{Build, Platform}

object ProjectGeneration {
  def generate(projectPath: Path, build: Build): Unit = {
    val bloopPath      = projectPath.resolve(".bloop")
    val buildPath      = projectPath.resolve("build")
    val bloopBuildPath = buildPath.resolve("bloop")

    Set(bloopPath, buildPath, bloopBuildPath)
      .foreach(Files.createDirectories(_))

    val resolvedIvyPath   = Coursier.DefaultIvyPath
    val resolvedCachePath = Coursier.DefaultCachePath

    val compilerDeps = ArtefactResolution.allCompilerDeps(build)
    val platformDeps = ArtefactResolution.allPlatformDeps(build)
    val libraryDeps  = ArtefactResolution.allLibraryDeps(build)

    val resolution =
      Coursier.resolveAndDownload(
        platformDeps ++ libraryDeps,
        Resolvers(),
        resolvedIvyPath,
        resolvedCachePath,
        optionalArtefacts = false,
        silent = true,
        Log.urgent
      )
    val compilerResolution =
      compilerDeps.map(
        d =>
          Coursier.resolveAndDownload(
            d,
            Resolvers(),
            resolvedIvyPath,
            resolvedCachePath,
            optionalArtefacts = false,
            silent = true,
            Log.urgent
          )
      )

    build.foreach {
      case (id, module) =>
        Bloop.buildModule(
          projectPath,
          bloopPath,
          buildPath,
          bloopBuildPath,
          build,
          resolution,
          compilerResolution,
          id,
          module.module,
          optionalArtefacts = false,
          Log.urgent
        )
    }
  }

  def toBuild(modules: Map[String, Build.Module]): Build = {
    val build = modules.mapValues(BuildConfig.inheritSettings(Build.Module()))
    build.mapValues(m => ModuleConfig(m, Paths.get(".")))
  }

  def generateJavaDepBloopProject(projectPath: Path): Build = {
    val modules = Map(
      "base" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        targets = List(Platform.JVM),
        javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5"))
      ),
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        moduleDeps = List("base"),
        jvm = Some(Build.Module()),
        test = Some(Build.Module(jvm = Some(Build.Module())))
      )
    )

    val build = toBuild(modules)
    generate(projectPath, build)
    build
  }

  /** Generate project compiling to JavaScript and JVM */
  def generateBloopCrossProject(projectPath: Path): Build = {
    val sourcePath = projectPath.resolve("src")
    Files.createDirectories(sourcePath)

    val modules = Map(
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaJsVersion = Some("0.6.26"),
        sources = List(sourcePath),
        targets = List(Platform.JVM, Platform.JavaScript)
      )
    )

    val build = toBuild(modules)

    generate(projectPath, build)

    FileUtils.write(
      sourcePath.resolve("Main.scala").toFile,
      """object Main extends App { println("hello") }""",
      "UTF-8"
    )

    build
  }
}
