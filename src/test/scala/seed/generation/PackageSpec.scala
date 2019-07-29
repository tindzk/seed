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

object PackageSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Package modules with same package") { _ =>
    val path = Paths.get("test/package-modules")

    val BuildConfig.Result(build, projectPath, _) =
      BuildConfig.load(path, Log.urgent).get
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
      projectPath,
      outputPath,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    val result = seed.cli.Build.build(
      path,
      Some(outputPath),
      List("app"),
      watch = false,
      tmpfs = false,
      Log.urgent,
      _ => _ => ()
    )

    for {
      _ <- RTS.unsafeRunToFuture(result.right.get)
      result <- {
        cli.Package.ui(
          Config(),
          outputPath,
          build,
          "app",
          Some(buildPath),
          libs = true,
          packageConfig,
          Log.urgent
        )

        util.TestProcessHelper
          .runCommand(buildPath, List("java", "-jar", "app.jar"))
      }
    } yield assertEquals(result.trim, "42")
  }
}
