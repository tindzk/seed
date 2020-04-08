package seed.generation

import java.nio.file.{Files, Paths}

import minitest.SimpleTestSuite
import seed.Cli.Command
import seed.{Log, cli}
import seed.config.BuildConfig
import seed.generation.BloopIntegrationSpec.packageConfig
import seed.generation.util.BuildUtil.tempPath
import seed.model.Config

object GenerateSpec extends SimpleTestSuite {
  test("Inherit scalaDeps in test module") {
    val config = BuildConfig
      .load(Paths.get("test", "test-inherit-deps"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("test-inherit-deps-generate")

    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )
  }
}
