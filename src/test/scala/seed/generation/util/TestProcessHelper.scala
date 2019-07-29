package seed.generation.util

import java.nio.file.Path
import java.util.concurrent.{Executors, Semaphore}

import scala.concurrent.{ExecutionContext, Future}
import seed.Log
import seed.cli.util.RTS
import seed.process.ProcessHelper

object TestProcessHelper {
  // Single-threaded execution context to avoid CI problems
  private val executor = Executors.newFixedThreadPool(1)
  implicit val ec      = ExecutionContext.fromExecutor(executor)

  // Use binary semaphore to synchronise test suite execution. Prevent Bloop
  // processes from running concurrently.
  val semaphore = new Semaphore(1)

  def runBloop(cwd: Path)(args: String*): Future[String] = {
    val sb = new StringBuilder
    val process =
      ProcessHelper.runBloop(cwd, Log.urgent, out => sb.append(out + "\n"))(
        args: _*
      )
    RTS.unsafeRunToFuture(process).map(_ => sb.toString)
  }

  def runCommand(cwd: Path, cmd: List[String]): Future[String] = {
    val sb = new StringBuilder
    val process = ProcessHelper.runCommmand(
      cwd,
      cmd,
      None,
      None,
      Log.urgent,
      out => sb.append(out + "\n")
    )
    RTS.unsafeRunToFuture(process).map(_ => sb.toString)
  }
}
