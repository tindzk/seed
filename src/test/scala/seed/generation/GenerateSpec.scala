package seed.generation

import java.nio.file.{Files, Path, Paths}

import minitest.SimpleTestSuite
import seed.Cli.Command
import seed.{Log, cli}
import seed.config.BuildConfig
import seed.generation.util.BuildUtil
import seed.model.Config
import seed.util.TestUtil

object GenerateSpec extends SimpleTestSuite {
  private val tempPath = BuildUtil.tempPath.resolve("generate")

  test("Inherit scalaDeps in test module") {
    val config = BuildConfig
      .load(Paths.get("test", "test-inherit-deps"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("test-inherit-deps-generate")

    Files.createDirectories(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(TestUtil.packageConfig),
      Log.urgent
    )
  }

  test("Generate Scala.js 1.0 test project") {
    val config = BuildConfig
      .load(Paths.get("test", "test-scalajs-10"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("test-scalajs-10")

    Files.createDirectories(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(TestUtil.packageConfig),
      Log.urgent
    )

    val bloopPath = buildPath.resolve(".bloop")

    val root  = util.BloopUtil.readJson(bloopPath.resolve("example-test.json"))
    val paths = root.project.classpath

    def artefact(path: Path): String = path.getFileName.toString
    assertEquals(
      paths.map(artefact),
      List(
        "scala-library-2.13.2.jar",
        "scala-reflect-2.13.2.jar",
        "scalajs-junit-test-runtime_2.13-1.0.1.jar",
        "munit_sjs1_2.13-0.7.4.jar",
        "scalajs-test-interface_2.13-1.0.1.jar",
        "scalajs-test-bridge_2.13-1.0.1.jar",
        "scalajs-library_2.13-1.0.1.jar",
        "example"
      )
    )
  }
}
