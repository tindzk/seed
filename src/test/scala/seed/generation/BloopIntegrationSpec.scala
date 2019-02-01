package seed.generation

import java.nio.file.{Files, Path, Paths}

import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils
import seed.cli
import seed.Cli.{Command, PackageConfig}
import seed.artefact.{ArtefactResolution, Coursier}
import seed.config.BuildConfig
import seed.generation.util.ProcessHelper
import seed.model.{Build, Config, Platform}

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

    compileAndRun(projectPath)
  }

  def compileAndRun(projectPath: Path) = {
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

  testAsync("Build project with compiler plug-in") {
    val (projectPath, build) = BuildConfig.load(
      Paths.get("test/example-paradise"))
    val buildPath = projectPath.resolve("build")
    if (Files.exists(buildPath)) FileUtils.deleteDirectory(buildPath.toFile)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Build.ui(Config(), projectPath, build, Command.Bloop(packageConfig))
    compileAndRun(projectPath)
  }
}
