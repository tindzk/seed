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

  testAsync("Generate and compile meta modules") { _ =>
    val projectPath = Paths.get("test/meta-module")
    util.ProjectGeneration.generateBloopProject(projectPath)
    compileAndRun(projectPath)
  }

  def compileAndRun(projectPath: Path) = {
    def compile =
      TestProcessHelper.runBloop(projectPath)("compile", "example").map { x =>
        assertEquals(x.contains("Compiled example-jvm"), true)
        assertEquals(x.contains("Compiled example-js"), true)
      }

    def run =
      TestProcessHelper.runBloop(projectPath)("run", "example-js", "example-jvm")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "hello"), 2)
        }

    for { _ <- compile; _ <- run } yield ()
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
}
