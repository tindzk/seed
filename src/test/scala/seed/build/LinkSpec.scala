package seed.build

import java.nio.file.Files

import scala.collection.mutable.ListBuffer
import minitest.TestSuite
import seed.Log
import seed.cli.Link
import seed.cli.util.{ConsoleOutput, RTS}
import seed.generation.util.{ProjectGeneration, TestProcessHelper}
import seed.model.{BuildEvent, Platform}
import seed.generation.util.TestProcessHelper.ec
import seed.generation.util.BuildUtil.tempPath

object LinkSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
  override def tearDown(env: Unit): Unit = ()

  testAsync("Link module and interpret Bloop events") { _ =>
    val projectPath = tempPath.resolve("module-link")
    Files.createDirectory(projectPath)

    val build = ProjectGeneration.generateBloopCrossProject(projectPath)

    var events = ListBuffer[BuildEvent]()

    val consoleOutput = new ConsoleOutput(Log.urgent, _ => ())
    val client = new BloopClient(
      consoleOutput,
      progress = false,
      projectPath,
      build,
      List("example" -> Platform.JavaScript),
      events += _
    )

    val program = Bsp
      .runBspServerAndConnect(client, projectPath, consoleOutput.log)
      .flatMap {
        case (bspProcess, socket, server) =>
          for {
            _ <- Link.linkPass(
              consoleOutput,
              client,
              server,
              build,
              projectPath,
              List("example" -> Platform.JavaScript),
              List("example" -> Platform.JavaScript),
              optimise = false,
              progress = false,
              consoleOutput.log,
              events += _
            )
            _ <- Bsp.shutdown(bspProcess, socket, server)
          } yield ()
      }

    for {
      _ <- RTS.unsafeRunToFuture(program)
    } yield {
      assertEquals(events.length, 3)
      assertEquals(
        events(0),
        BuildEvent.Compiling("example", Platform.JavaScript)
      )
      assertEquals(
        events(1),
        BuildEvent.Compiled("example", Platform.JavaScript)
      )
      assert(events(2).isInstanceOf[BuildEvent.Linked])
      require(
        events(2)
          .asInstanceOf[BuildEvent.Linked]
          .path
          .endsWith("module-link/build/example.js")
      )
    }
  }
}
