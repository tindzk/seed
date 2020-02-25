package seed.generation

import java.nio.file.{Files, Path, Paths}

import bloop.config.Config.JsConfig
import bloop.config.ConfigEncoderDecoders
import minitest.TestSuite
import org.apache.commons.io.FileUtils
import seed.{Log, LogLevel, cli}
import seed.Cli.{Command, PackageConfig}
import seed.cli.util.RTS
import seed.config.BuildConfig
import seed.generation.util.TestProcessHelper
import seed.generation.util.TestProcessHelper.ec
import seed.model.Config

import scala.concurrent.Future
import seed.generation.util.BuildUtil.tempPath

import scala.collection.mutable.ListBuffer

object BloopIntegrationSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
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
      TestProcessHelper
        .runBloop(projectPath)("run", "example-js", "example-jvm")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "hello"), 2)
        }

    for { _ <- compile; _ <- run } yield ()
  }

  private[seed] val packageConfig = PackageConfig(
    tmpfs = false,
    silent = false,
    ivyPath = None,
    cachePath = None
  )

  test(
    "Generate project with duplicate transitive module dependencies"
  ) { _ =>
    val config =
      BuildConfig
        .load(Paths.get("test/duplicate-transitive-dep"), Log.urgent)
        .get
    import config._
    val buildPath = tempPath.resolve("duplicate-transitive-dep")
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

    val bloopBuildPath = buildPath.resolve("build").resolve("bloop")

    val bloopPath = buildPath.resolve(".bloop")
    val root      = readBloopJson(bloopPath.resolve("root.json"))
    val paths     = root.project.classpath.filter(_.startsWith(buildPath))
    assertEquals(
      paths,
      List(
        bloopBuildPath.resolve("a"),
        bloopBuildPath.resolve("b"),
        bloopBuildPath.resolve("shared")
      )
    )
  }

  testAsync("Generate and compile meta modules") { _ =>
    val projectPath = tempPath.resolve("meta-module")
    util.ProjectGeneration.generateBloopCrossProject(projectPath)
    compileAndRun(projectPath)
  }

  testAsync(
    "Build project with compiler plug-in defined on cross-platform module"
  ) { _ =>
    val config =
      BuildConfig.load(Paths.get("test/example-paradise"), Log.urgent).get
    import config._
    val buildPath = tempPath.resolve("example-paradise")
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
    compileAndRun(buildPath)
  }

  testAsync("Build project with compiler plug-in defined on platform modules") {
    _ =>
      val config = BuildConfig
        .load(Paths.get("test/example-paradise-platform"), Log.urgent)
        .get
      import config._
      val buildPath = tempPath.resolve("example-paradise-platform")
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
      compileAndRun(buildPath)
  }

  testAsync("Link JavaScript modules with custom target path") { _ =>
    val config =
      BuildConfig.load(Paths.get("test/submodule-output-path"), Log.urgent).get
    import config._
    val buildPath = tempPath.resolve("submodule-output-path")
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
    TestProcessHelper
      .runBloop(buildPath)("run", "app", "base", "base2")
      .map { x =>
        assertEquals(x.split("\n").count(_ == "hello"), 3)
        assert(
          Files
            .exists(buildPath.resolve("build").resolve("js").resolve("app.js"))
        )
        assert(
          Files
            .exists(buildPath.resolve("build").resolve("js").resolve("base.js"))
        )
        assert(Files.exists(buildPath.resolve("build").resolve("base2.js")))
      }
  }

  testAsync("Build project with overridden compiler plug-in version") { _ =>
    val projectPath = Paths.get("test/example-paradise-versions")
    val result      = BuildConfig.load(projectPath, Log.urgent).get
    import result._
    val buildPath = tempPath.resolve("example-paradise-versions")
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

    val bloopPath = buildPath.resolve(".bloop")

    val macrosJvm  = readBloopJson(bloopPath.resolve("macros-jvm.json"))
    val macrosJs   = readBloopJson(bloopPath.resolve("macros-js.json"))
    val exampleJvm = readBloopJson(bloopPath.resolve("example-jvm.json"))
    val exampleJs  = readBloopJson(bloopPath.resolve("example-js.json"))

    def getFileName(path: String): String = path.drop(path.lastIndexOf('/') + 1)

    assertEquals(
      macrosJvm.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.0.jar")
    )
    assertEquals(
      macrosJs.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.1.jar")
    )
    assertEquals(
      exampleJvm.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.0.jar")
    )
    assertEquals(
      exampleJs.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.1.jar")
    )
    assertEquals(
      exampleJs.project.platform.get.config.asInstanceOf[JsConfig].version,
      "0.6.26"
    )

    def checkResolutionArtefacts(configFile: bloop.config.Config.File): Unit = {
      val resolution = configFile.project.resolution.get.modules
      configFile.project.classpath
        .filter(_.toString.endsWith(".jar"))
        .foreach { cp =>
          assert(
            resolution.exists(_.artifacts.exists(_.path == cp)),
            s"Missing artefact: $cp"
          )

          // By default, only fetch class artefacts unless user enabled
          // `optionalArtefacts` in Seed configuration
          assert(
            !resolution.exists(_.artifacts.exists(_.classifier.isDefined)),
            s"Classifier should be empty: $cp"
          )
        }
    }

    checkResolutionArtefacts(macrosJvm)
    checkResolutionArtefacts(macrosJs)
    checkResolutionArtefacts(exampleJvm)
    checkResolutionArtefacts(exampleJs)

    compileAndRun(buildPath)
  }

  testAsync("Build modules with different Scala versions") { _ =>
    val config = BuildConfig
      .load(Paths.get("test/multiple-scala-versions"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("multiple-scala-versions-bloop")

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
    TestProcessHelper
      .runBloop(buildPath)("run", "module211", "module212")
      .map { x =>
        val lines = x.split("\n").toList
        assert(lines.contains("2.11.11"))
        assert(lines.contains("2.12.8"))
      }
  }

  def buildCustomTarget(
    name: String,
    expectFailure: Boolean = false
  ): Future[Either[List[String], List[String]]] = {
    val path = Paths.get("test", name)

    val config = BuildConfig.load(path, Log.urgent).get
    import config._
    val buildPath = tempPath.resolve(name)
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

    val lines = ListBuffer[String]()

    val result = seed.cli.Build.build(
      path,
      Some(buildPath),
      List("demo"),
      watch = false,
      tmpfs = false,
      progress = false,
      new Log(lines += _, identity, LogLevel.Warn, false),
      stdOut => lines ++= stdOut.split('\n'),
      _ => ()
    )

    val uio = result.right.get

    if (expectFailure)
      RTS.unsafeRunToFuture(uio).failed.map { _ =>
        assert(lines.nonEmpty)
        Left(lines.toList)
      } else {
      RTS.unsafeRunSync(uio)

      val generatedFile = projectPath.resolve("demo").resolve("Generated.scala")
      assert(Files.exists(generatedFile))

      TestProcessHelper
        .runBloop(buildPath)("run", "demo")
        .map { x =>
          Files.delete(generatedFile)
          assertEquals(lines.toList, List())
          Right(x.split("\n").toList)
        }
    }
  }

  testAsync("Build project with custom class target") { _ =>
    buildCustomTarget("custom-class-target").map(
      lines => assertEquals(lines.right.get.count(_ == "42"), 1)
    )
  }

  testAsync(
    "Build project with custom class target (shared by multiple modules)"
  ) { _ =>
    buildCustomTarget("custom-class-target-shared").map(
      lines =>
        assertEquals(
          lines.right.get.map(_.split("test/").last),
          List(
            "custom-class-target-shared/template1",
            "custom-class-target-shared/template2"
          )
        )
    )
  }

  testAsync("Build project with custom command target") { _ =>
    buildCustomTarget("custom-command-target").map { lines =>
      assertEquals(lines.right.get.count(_ == "42"), 1)

      val path = tempPath
        .resolve("custom-command-target")
        .resolve(".bloop")
        .resolve("demo.json")
      val result = readBloopJson(path)

      // Do not include the `utils` classpath since the module is only a custom
      // build target and does not have a JVM target.
      assert(!result.project.classpath.exists(_.toString.contains("/utils")))
      assert(result.project.classpath.forall(Files.exists(_)))

      // Should not include "utils" dependency since it does not have any
      // Scala sources and no Bloop module.
      assertEquals(result.project.dependencies, List())
    }
  }

  testAsync("Build project with failing custom command target") { _ =>
    buildCustomTarget("custom-command-target-fail", expectFailure = true)
      .map(_ => ())
  }

  testAsync("Build project with failing compilation") { _ =>
    buildCustomTarget("compilation-failure", expectFailure = true).map(
      log =>
        // Must indicate correct position
        assert(
          log.left.get
            .exists(_.contains("[2:41]: not found: value invalidIdentifier"))
        )
    )
  }

  testAsync("Generate non-JVM project") { _ =>
    val config = BuildConfig
      .load(Paths.get("test/shared-module"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("shared-module-bloop")
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

    TestProcessHelper
      .runBloop(buildPath)("run", "example-js", "example-native")
      .map { output =>
        val lines = output.split("\n")
        assert(lines.contains("js"))
        assert(lines.contains("native"))
      }
  }

  test("Inherit classpaths of platform-specific base modules") { _ =>
    val result = BuildConfig
      .load(Paths.get("test").resolve("platform-module-deps"), Log.urgent)
      .get
    val buildPath = tempPath.resolve("platform-module-deps-bloop")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      buildPath,
      result.resolvers,
      result.build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    val bloopBuildPath = buildPath.resolve("build").resolve("bloop")

    val bloopPath = buildPath.resolve(".bloop")

    val root  = readBloopJson(bloopPath.resolve("example.json"))
    val paths = root.project.classpath.filter(_.startsWith(buildPath))

    assertEquals(
      paths,
      List(
        bloopBuildPath.resolve("base"),
        bloopBuildPath.resolve("core")
      )
    )
  }

  test("Override target platforms in test module") { _ =>
    val config =
      BuildConfig
        .load(Paths.get("test", "test-module-override-targets"), Log.urgent)
        .get
    import config._
    val buildPath = tempPath.resolve("test-module-override-targets")
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
