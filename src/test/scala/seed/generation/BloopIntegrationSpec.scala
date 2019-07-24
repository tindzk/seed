package seed.generation

import java.nio.file.{Files, Path, Paths}

import bloop.config.ConfigEncoderDecoders
import minitest.TestSuite
import org.apache.commons.io.FileUtils
import seed.{Log, cli}
import seed.Cli.{Command, PackageConfig}
import seed.cli.util.Exit
import seed.config.BuildConfig
import seed.generation.util.TestProcessHelper
import seed.generation.util.TestProcessHelper.ec
import seed.model.Config

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import seed.generation.util.BuildUtil.tempPath

object BloopIntegrationSpec extends TestSuite[Unit] {
  Exit.TestCases = true

  override def setupSuite(): Unit = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit = ()
  override def tearDown(env: Unit): Unit = ()

  def readBloopJson(path: Path): bloop.config.Config.File = {
    val content = FileUtils.readFileToString(path.toFile, "UTF-8")

    import io.circe.parser._
    decode[bloop.config.Config.File](content)(
      ConfigEncoderDecoders.allDecoder
    ).right.get
  }

  def compileAndRun(projectPath: Path) = {
    def compile =
      TestProcessHelper.runBloop(projectPath)("compile", "example").map { x =>
        assert(x.contains("Compiled example-jvm"))
        assert(x.contains("Compiled example-js"))
      }

    def run =
      TestProcessHelper.runBloop(projectPath)("run", "example-js", "example-jvm")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "hello"), 2)
        }

    for { _ <- compile; _ <- run } yield ()
  }

  testAsync("Generate and compile meta modules") { _ =>
    val projectPath = tempPath.resolve("meta-module")
    util.ProjectGeneration.generateBloopCrossProject(projectPath)
    compileAndRun(projectPath)
  }

  testAsync("Build project with compiler plug-in defined on cross-platform module") { _ =>
    val BuildConfig.Result(build, projectPath, _) = BuildConfig.load(
      Paths.get("test/example-paradise"), Log.urgent).get
    val buildPath = tempPath.resolve("example-paradise")
    Files.createDirectory(buildPath)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, buildPath, build,
      Command.Bloop(packageConfig), Log.urgent)
    compileAndRun(buildPath)
  }

  testAsync("Build project with compiler plug-in defined on platform modules") { _ =>
    val BuildConfig.Result(build, projectPath, _) = BuildConfig.load(
      Paths.get("test/example-paradise-platform"), Log.urgent).get
    val buildPath = tempPath.resolve("example-paradise-platform")
    Files.createDirectory(buildPath)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, buildPath, build,
      Command.Bloop(packageConfig), Log.urgent)
    compileAndRun(buildPath)
  }

  testAsync("Link JavaScript modules with custom target path") { _ =>
    val BuildConfig.Result(build, projectPath, _) = BuildConfig.load(
      Paths.get("test/submodule-output-path"), Log.urgent).get
    val buildPath = tempPath.resolve("submodule-output-path")
    Files.createDirectory(buildPath)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, buildPath, build,
      Command.Bloop(packageConfig), Log.urgent)
    TestProcessHelper.runBloop(buildPath)("run", "app", "base", "base2")
      .map { x =>
        assertEquals(x.split("\n").count(_ == "hello"), 3)
        assert(Files.exists(buildPath.resolve("build").resolve("js").resolve("app.js")))
        assert(Files.exists(buildPath.resolve("build").resolve("js").resolve("base.js")))
        assert(Files.exists(buildPath.resolve("build").resolve("base2.js")))
      }
  }

  testAsync("Build project with overridden compiler plug-in version") { _ =>
    val projectPath = Paths.get("test/example-paradise-versions")
    val BuildConfig.Result(build, _, _) =
      BuildConfig.load(projectPath, Log.urgent).get
    val buildPath = tempPath.resolve("example-paradise-versions")
    Files.createDirectory(buildPath)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, buildPath, build,
      Command.Bloop(packageConfig), Log.urgent)

    val bloopPath = buildPath.resolve(".bloop")

    val macrosJvm = readBloopJson(bloopPath.resolve("macros-jvm.json"))
    val macrosJs = readBloopJson(bloopPath.resolve("macros-js.json"))
    val exampleJvm = readBloopJson(bloopPath.resolve("example-jvm.json"))
    val exampleJs = readBloopJson(bloopPath.resolve("example-js.json"))

    def getFileName(path: String): String = path.drop(path.lastIndexOf('/') + 1)

    assertEquals(
      macrosJvm.project.scala.get.options.filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.0.jar"))
    assertEquals(
      macrosJs.project.scala.get.options.filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.1.jar"))
    assertEquals(
      exampleJvm.project.scala.get.options.filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.0.jar"))
    assertEquals(
      exampleJs.project.scala.get.options.filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.1.jar"))

    compileAndRun(buildPath)
  }

  testAsync("Build modules with different Scala versions") { _ =>
    val BuildConfig.Result(build, projectPath, _) = BuildConfig.load(
      Paths.get("test/multiple-scala-versions"), Log.urgent).get
    val buildPath = tempPath.resolve("multiple-scala-versions-bloop")
    Files.createDirectory(buildPath)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, buildPath, build,
      Command.Bloop(packageConfig), Log.urgent)
    TestProcessHelper.runBloop(buildPath)("run", "module211", "module212")
      .map { x =>
        val lines = x.split("\n").toList
        assert(lines.contains("2.11.11"))
        assert(lines.contains("2.12.8"))
      }
  }

  def buildCustomTarget(name: String, expectFailure: Boolean = false): Future[Unit] = {
    val path = Paths.get(s"test/$name")

    val BuildConfig.Result(build, projectPath, _) =
      BuildConfig.load(path, Log.urgent).get
    val buildPath = tempPath.resolve(name)
    Files.createDirectory(buildPath)
    val generatedFile = projectPath.resolve("demo").resolve("Generated.scala")
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, buildPath, build,
      Command.Bloop(packageConfig), Log.urgent)

    val result = seed.cli.Build.build(
      path,
      Some(buildPath),
      List("demo"),
      watch = false,
      tmpfs = false,
      if (expectFailure) Log.silent else Log.urgent,
      _ => _ => ())

    val future = result.right.get

    if (expectFailure) future.failed.map(_ => ())
    else {
      Await.result(future, 30.seconds)
      assert(Files.exists(generatedFile))

      TestProcessHelper.runBloop(buildPath)("run", "demo")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "42"), 1)
          Files.delete(generatedFile)
        }
    }
  }

  testAsync("Build project with custom class target") { _ =>
    buildCustomTarget("custom-class-target")
  }

  testAsync("Build project with custom command target") { _ =>
    buildCustomTarget("custom-command-target").map { _ =>
      val path = tempPath
        .resolve("custom-command-target")
        .resolve(".bloop")
        .resolve("demo.json")
      val result = readBloopJson(path)

      // Should not include "utils" dependency since it does not have any
      // Scala sources and no Bloop module.
      assertEquals(result.project.dependencies, List())
    }
  }

  testAsync("Build project with failing custom command target") { _ =>
    buildCustomTarget("custom-command-target-fail", expectFailure = true)
  }
}
