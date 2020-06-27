package seed.generation

import java.nio.file.{Files, Paths}

import minitest.TestSuite
import seed.{Log, cli}
import seed.Cli.{Command, PackageConfig}
import seed.cli.util.RTS
import seed.config.BuildConfig
import seed.generation.util.TestProcessHelper
import seed.generation.util.TestProcessHelper.ec
import seed.model.Config
import seed.generation.util.BuildUtil.tempPath
import seed.model.Build.Resolvers

object PackageSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Package modules with same package") { _ =>
    val path = Paths.get("test", "package-modules")

    val config     = BuildConfig.load(path, Log.urgent).get
    val outputPath = tempPath.resolve("package-modules")
    Files.createDirectory(outputPath)
    val buildPath = outputPath.resolve("build")
    val packageConfig = PackageConfig(
      tmpfs = false,
      silent = false,
      ivyPath = None,
      cachePath = None
    )
    cli.Generate.ui(
      Config(),
      config.projectPath,
      outputPath,
      config.resolvers,
      config.build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    for {
      result <- {
        cli.Package.ui(
          Config(),
          outputPath,
          Resolvers(),
          config.build,
          "app",
          Some(buildPath),
          libs = true,
          progress = false,
          packageConfig,
          Log.urgent
        )

        util.TestProcessHelper
          .runCommand(buildPath, List("java", "-jar", "app.jar"))
      }
    } yield assertEquals(result.trim, "42")
  }

  testAsync("Package resources") { _ =>
    val path = Paths.get("test", "example-resources")

    val config     = BuildConfig.load(path, Log.urgent).get
    val outputPath = tempPath.resolve("example-resources-package")
    Files.createDirectory(outputPath)
    val buildPath = outputPath.resolve("build")
    val packageConfig = PackageConfig(
      tmpfs = false,
      silent = false,
      ivyPath = None,
      cachePath = None
    )
    cli.Generate.ui(
      Config(),
      config.projectPath,
      outputPath,
      config.resolvers,
      config.build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    for {
      result <- {
        cli.Package.ui(
          Config(),
          outputPath,
          Resolvers(),
          config.build,
          "example",
          Some(buildPath),
          libs = true,
          progress = false,
          packageConfig,
          Log.urgent
        )

        util.TestProcessHelper
          .runCommand(buildPath, List("java", "-jar", "example.jar"))
      }
    } yield assertEquals(result.trim, "hello world")
  }
}
