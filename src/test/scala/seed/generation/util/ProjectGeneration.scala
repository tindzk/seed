package seed.generation.util

import java.nio.file.{Files, Path}

import org.apache.commons.io.FileUtils
import seed.artefact.{ArtefactResolution, Coursier}
import seed.generation.Bloop
import seed.model.Build.JavaDep
import seed.model.{Build, Platform}

object ProjectGeneration {
  def generate(projectPath: Path, build: Build): Unit = {
    if (Files.exists(projectPath)) FileUtils.deleteDirectory(projectPath.toFile)

    val bloopPath = projectPath.resolve(".bloop")
    val buildPath = projectPath.resolve("build")

    Set(bloopPath, buildPath).foreach(Files.createDirectories(_))

    val resolvedIvyPath = Coursier.DefaultIvyPath
    val resolvedCachePath = Coursier.DefaultCachePath

    val compilerDeps0 = ArtefactResolution.allCompilerDeps(build)
    val platformDeps = ArtefactResolution.allPlatformDeps(build)
    val libraryDeps  = ArtefactResolution.allLibraryDeps(build)

    val resolution =
      Coursier.resolveAndDownload(platformDeps ++ libraryDeps, build.resolvers,
        resolvedIvyPath, resolvedCachePath, optionalArtefacts = false,
        silent = true)
    val compilerResolution =
      compilerDeps0.map(d =>
        Coursier.resolveAndDownload(d, build.resolvers, resolvedIvyPath,
          resolvedCachePath, optionalArtefacts = false, silent = true))

    build.module.foreach { case (id, module) =>
      Bloop.buildModule(
        projectPath,
        bloopPath,
        buildPath,
        build,
        resolution,
        compilerResolution,
        id,
        module)
    }
  }

  def generateJavaDepBloopProject(projectPath: Path): Unit = {
    val build = Build(
      project = Build.Project("2.12.8"),
      module = Map(
        "base" -> Build.Module(
          targets = List(Platform.JVM),
          javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5"))),
        "example" -> Build.Module(
          moduleDeps = List("base"),
          targets = List(Platform.JVM),
          jvm = Some(Build.Module()),
          test = Some(Build.Module(jvm = Some(Build.Module()))))))

    generate(projectPath, build)
  }

  /** Generate project compiling to JavaScript and JVM */
  def generateBloopCrossProject(projectPath: Path): Build = {
    val sourcePath = projectPath.resolve("src")
    Files.createDirectories(sourcePath)

    val build = Build(
      project = Build.Project(
        "2.12.8", scalaJsVersion = Some("0.6.26")),
      module = Map("example" -> Build.Module(
        sources = List(sourcePath),
        targets = List(Platform.JVM, Platform.JavaScript))))

    generate(projectPath, build)

    FileUtils.write(sourcePath.resolve("Main.scala").toFile,
      """object Main extends App { println("hello") }""",
      "UTF-8")

    build
  }
}
