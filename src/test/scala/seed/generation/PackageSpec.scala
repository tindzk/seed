package seed.generation

import java.nio.file.{Files, Paths}

import minitest.TestSuite
import org.apache.commons.io.FileUtils
import seed.{Log, cli}
import seed.Cli.{Command, PackageConfig}
import seed.cli.util.Exit
import seed.config.BuildConfig
import seed.generation.util.TestProcessHelper
import seed.generation.util.TestProcessHelper.ec
import seed.model.Config

object PackageSpec extends TestSuite[Unit] {
  Exit.TestCases = true

  override def setupSuite(): Unit = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Package modules with same package") { _ =>
    val path = Paths.get("test/package-modules")

    val BuildConfig.Result(build, projectPath, _) =
      BuildConfig.load(path, Log.urgent).get
    val buildPath = projectPath.resolve("build")
    if (Files.exists(buildPath)) FileUtils.deleteDirectory(buildPath.toFile)
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Generate.ui(Config(), projectPath, build, Command.Bloop(packageConfig),
      Log.urgent)

    val result = seed.cli.Build.build(
      path,
      List("app"),
      watch = false,
      tmpfs = false,
      Log.urgent,
      _ => _ => ())

    for {
      _ <- result.right.get
      result <- {
        cli.Package.ui(Config(), projectPath, build, "app", None,
          libs = true, packageConfig, Log.urgent)

        util.TestProcessHelper.runCommand(
          buildPath.resolve("dist"),
          List("java", "-jar", "app.jar"))
      }
    } yield assertEquals(result.trim, "42")
  }
}
