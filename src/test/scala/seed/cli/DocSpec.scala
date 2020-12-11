package seed.cli

import java.nio.file.{Files, Paths}

import minitest.TestSuite
import seed.Cli.Command
import seed.cli.util.RTS
import seed.config.BuildConfig
import seed.{Log, cli}
import seed.generation.util.BuildUtil.tempPath
import seed.model.Config
import seed.generation.util.TestProcessHelper
import seed.util.TestUtil

object DocSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
  override def tearDown(env: Unit): Unit = ()

  private val log = Log.urgent

  private def testProject(name: String, modules: List[String]) = {
    val projectPath = Paths.get("test").resolve(name)

    val config = BuildConfig.load(projectPath, log).get
    import config._

    val buildPath = tempPath.resolve(name + "-doc")
    if (!Files.exists(buildPath)) Files.createDirectory(buildPath)

    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(TestUtil.packageConfig),
      Log.urgent
    )

    val command = Command.Doc(TestUtil.packageConfig, Some(buildPath), modules)
    val uio = Doc.ui(
      config,
      buildPath,
      Config(),
      command,
      command.packageConfig,
      progress = false,
      log
    )
    RTS.unsafeRunToFuture(uio)
  }

  testAsync("Document Scala 2.11 project with Macro Paradise") { _ =>
    testProject("example-paradise", List("macros", "example"))
  }

  testAsync("Document Scala 2.12 project using Typelevel compiler") { _ =>
    testProject("compiler-options", List("demo:jvm"))
  }

  testAsync("Document Scala.js 2.13 project") { _ =>
    testProject("submodule-output-path", List("app:js"))
  }
}
