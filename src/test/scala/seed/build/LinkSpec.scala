package seed.build

import java.nio.file.Paths

import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import minitest.TestSuite
import seed.Log
import seed.cli.util.BloopCli
import seed.generation.util.{ProjectGeneration, TestProcessHelper}
import seed.model.{BuildEvent, Platform}
import seed.generation.util.TestProcessHelper.ec

object LinkSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Link module and interpret Bloop events") { _ =>
    val projectPath = Paths.get("test/module-link")
    val build = ProjectGeneration.generateBloopCrossProject(projectPath)

    var events = ListBuffer[BuildEvent]()
    def onStdOut(output: String): Unit =
      BloopCli.parseStdOut(build)(output).foreach(events += _)

    val process = BloopCli.link(
      build, projectPath, List("example-js"), watch = false, Log.urgent, onStdOut)

    assert(process.isDefined)

    for {
      _ <- process.get.success
    } yield {
      require(events.length == 3)
      require(events(0) == BuildEvent.Compiling("example", Platform.JavaScript))
      require(events(1) == BuildEvent.Compiled("example", Platform.JavaScript))
      require(events(2).isInstanceOf[BuildEvent.Linked])
      require(events(2).asInstanceOf[BuildEvent.Linked]
        .path.endsWith("test/module-link/build/example.js"))
    }
  }
}
