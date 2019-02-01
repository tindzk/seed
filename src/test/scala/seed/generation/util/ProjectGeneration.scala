package seed.generation.util

import java.nio.file.{Files, Path}

import org.apache.commons.io.FileUtils
import seed.artefact.{ArtefactResolution, Coursier}
import seed.generation.Bloop
import seed.model.{Build, Platform}

object ProjectGeneration {
  /** Generate project compiling to JavaScript and JVM */
  def generateBloopProject(projectPath: Path): Build = {
    if (Files.exists(projectPath)) FileUtils.deleteDirectory(projectPath.toFile)

    val bloopPath = projectPath.resolve(".bloop")
    val buildPath = projectPath.resolve("build")
    val sourcePath = projectPath.resolve("src")

    Set(bloopPath, buildPath, sourcePath)
      .foreach(Files.createDirectories(_))

    val build = Build(
      project = Build.Project(
        "2.12.8", scalaJsVersion = Some("0.6.26")),
      module = Map("example" -> Build.Module(
        sources = List(sourcePath),
        targets = List(Platform.JVM, Platform.JavaScript))))

    val resolvedIvyPath = Coursier.DefaultIvyPath
    val resolvedCachePath = Coursier.DefaultCachePath

    val compilerDeps0 = ArtefactResolution.allCompilerDeps(build)
    val platformDeps = ArtefactResolution.allPlatformDeps(build)
    val libraryDeps  = ArtefactResolution.allLibraryDeps(build)

    val resolution =
      Coursier.resolveAndDownload(platformDeps ++ libraryDeps, build.resolvers,
        resolvedIvyPath, resolvedCachePath, false)
    val compilerResolution =
      compilerDeps0.map(d =>
        Coursier.resolveAndDownload(d, build.resolvers, resolvedIvyPath,
          resolvedCachePath, false))

    Bloop.buildModule(
      projectPath,
      bloopPath,
      buildPath,
      build,
      resolution,
      compilerResolution,
      build.module.keys.head,
      build.module.values.head)

    FileUtils.write(sourcePath.resolve("Main.scala").toFile,
      """object Main extends App { println("hello") }""",
      "UTF-8")

    build
  }
}
