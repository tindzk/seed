package seed.generation.util

import java.nio.file.Path
import java.util.concurrent.{Executors, Semaphore, TimeUnit}

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

  private val scheduler = Executors.newScheduledThreadPool(1)
  def schedule(seconds: Int)(f: => Unit): Unit =
    scheduler.schedule({ () => f }: Runnable, seconds, TimeUnit.SECONDS)

  /**
    * Work around a CI problem where onExit() does not get called on
    * [[seed.process.ProcessHandler]].
    */
  def scheduleTermination(process: ProcessHelper.Process): Unit =
    TestProcessHelper.schedule(60) {
      if (process.isRunning) {
        Log.error(s"Process did not terminate after 60s")
        Log.error("Forcing termination...")
        process.kill()
      }
    }

  def runBloop(cwd: Path)(args: String*): Future[String] = {
    val sb = new StringBuilder
    val process = ProcessHelper.runBloop(cwd,
      { out =>
        Log.info(s"Process output: $out")
        sb.append(out + "\n")
      })(args: _*)
    scheduleTermination(process)

    process.termination.flatMap { statusCode =>
      if (process.killed || statusCode == 0) Future.successful(sb.toString)
      else Future.failed(new Exception("Status code: " + statusCode))
    }
  }
}
