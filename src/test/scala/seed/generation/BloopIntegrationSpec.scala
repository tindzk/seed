package seed.generation

import java.nio.file.{Files, Path, Paths}

import minitest.TestSuite
import org.apache.commons.io.FileUtils
import seed.{Log, cli}
import seed.Cli.{Command, PackageConfig}
import seed.config.BuildConfig
import seed.generation.util.TestProcessHelper
import seed.generation.util.TestProcessHelper.ec
import seed.model.Config

object BloopIntegrationSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit = ()
  override def tearDown(env: Unit): Unit = ()

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
    val projectPath = Paths.get("test/meta-module")
    util.ProjectGeneration.generateBloopCrossProject(projectPath)
    compileAndRun(projectPath)
  }

  testAsync("Build project with compiler plug-in") { _ =>
    val (projectPath, build) = BuildConfig.load(
      Paths.get("test/example-paradise"), Log).get
    val buildPath = projectPath.resolve("build")
    if (Files.exists(buildPath)) FileUtils.deleteDirectory(buildPath.toFile)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Build.ui(Config(), projectPath, build, Command.Bloop(packageConfig))
    compileAndRun(projectPath)
  }

  testAsync("Build modules with different Scala versions") { _ =>
    val (projectPath, build) = BuildConfig.load(
      Paths.get("test/multiple-scala-versions"), Log).get
    val buildPath = projectPath.resolve("build")
    if (Files.exists(buildPath)) FileUtils.deleteDirectory(buildPath.toFile)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Build.ui(Config(), projectPath, build, Command.Bloop(packageConfig))
    TestProcessHelper.runBloop(projectPath)("run", "module211", "module212")
      .map { x =>
        val lines = x.split("\n").toList
        assert(lines.contains("2.11.11"))
        assert(lines.contains("2.12.8"))
      }
  }
}
