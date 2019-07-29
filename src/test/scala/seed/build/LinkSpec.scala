package seed.build

import java.nio.file.Files

import scala.collection.mutable.ListBuffer
import minitest.TestSuite
import seed.Log
import seed.cli.util.{BloopCli, RTS}
import seed.generation.util.{ProjectGeneration, TestProcessHelper}
import seed.model.{BuildEvent, Platform}
import seed.generation.util.TestProcessHelper.ec
import seed.generation.util.BuildUtil.tempPath

object LinkSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Link module and interpret Bloop events") { _ =>
    val projectPath = tempPath.resolve("module-link")
    Files.createDirectory(projectPath)

    val build = ProjectGeneration.generateBloopCrossProject(projectPath)

    var events = ListBuffer[BuildEvent]()
    def onStdOut(output: String): Unit =
      BloopCli.parseStdOut(build)(output).foreach(events += _)

    val process = BloopCli.link(
      build, projectPath, List("example-js"), watch = false, Log.urgent, onStdOut)

    assert(process.isDefined)

    for {
      _ <- RTS.unsafeRunToFuture(process.get)
    } yield {
      require(events.length == 3)
      require(events(0) == BuildEvent.Compiling("example", Platform.JavaScript))
      require(events(1) == BuildEvent.Compiled("example", Platform.JavaScript))
      require(events(2).isInstanceOf[BuildEvent.Linked])
      require(events(2).asInstanceOf[BuildEvent.Linked]
        .path.endsWith("module-link/build/example.js"))
    }
  }
}
