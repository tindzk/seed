package seed.generation.util

import java.nio.file.Path
import java.util.concurrent.{Executors, Semaphore}

import scala.concurrent.{ExecutionContext, Future}
import seed.Log
import seed.process.ProcessHelper

object TestProcessHelper {
  // Single-threaded execution context to avoid CI problems
  private val executor = Executors.newFixedThreadPool(1)
  implicit val ec = ExecutionContext.fromExecutor(executor)

  // Use binary semaphore to synchronise test suite execution. Prevent Bloop
  // processes from running concurrently.
  val semaphore = new Semaphore(1)

  def runBloop(cwd: Path)(args: String*): Future[String] = {
    val sb = new StringBuilder
    val process = ProcessHelper.runBloop(cwd,
      Log,
      { out =>
        Log.info(s"Process output: $out")
        sb.append(out + "\n")
      })(args: _*)

    process.success.map(_ => sb.toString)
  }
}
