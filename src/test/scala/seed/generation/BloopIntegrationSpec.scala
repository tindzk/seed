package seed.generation

import java.nio.file.{Files, Paths}

import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils
import seed.artefact.{ArtefactResolution, Coursier}
import seed.generation.util.ProcessHelper
import seed.model.{Build, Platform}

import scala.concurrent.ExecutionContext.Implicits._

object BloopIntegrationSpec extends SimpleTestSuite {
  testAsync("Generate and compile meta modules") {
    val projectPath = Paths.get("test/meta-module")
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

    val compilerDeps = ArtefactResolution.allCompilerDeps(build)
    val platformDeps = ArtefactResolution.allPlatformDeps(build)
    val libraryDeps  = ArtefactResolution.allLibraryDeps(build)

    val resolution =
      Coursier.resolveAndDownload(platformDeps ++ libraryDeps, build.resolvers,
        resolvedIvyPath, resolvedCachePath, false)
    val compilerResolution =
      compilerDeps.map(d =>
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

    def compile =
      ProcessHelper.runBloop(projectPath)("compile", "example").map { x =>
        assertEquals(x.contains("Compiled example-jvm"), true)
        assertEquals(x.contains("Compiled example-js"), true)
      }

    def run =
      ProcessHelper.runBloop(projectPath)("run", "example-js", "example-jvm")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "hello"), 2)
        }

    for { _ <- compile; _ <- run } yield ()
  }
}
