package seed.generation

import java.nio.file.Files

import minitest.TestSuite
import seed.cli.util.RTS
import seed.generation.util.{CustomTargetUtil, TestProcessHelper}
import seed.generation.util.TestProcessHelper.ec

object BuildTargetSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Build custom target") { _ =>
    val (config, _, uio) =
      CustomTargetUtil.buildCustomTarget(
        "custom-command-target",
        "utils",
        "build-target"
      )
    import config._
    RTS.unsafeRunToFuture(uio).map { _ =>
      val generatedFile = projectPath.resolve("demo").resolve("Generated.scala")
      assert(Files.exists(generatedFile))
      Files.delete(generatedFile)
    }
  }
}
