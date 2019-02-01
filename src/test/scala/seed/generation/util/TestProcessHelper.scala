package seed.generation.util

import java.nio.file.Path
import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._

import seed.Log
import seed.process.ProcessHelper

object TestProcessHelper {
  val scheduler = Executors.newScheduledThreadPool(1)
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

  def runBloop(cwd: Path, silent: Boolean = false)
              (args: String*): Future[String] = {
    val sb = new StringBuilder
    val process = ProcessHelper.runBloop(cwd, silent,
      out => sb.append(out + "\n"))(args: _*)
    scheduleTermination(process)

    process.termination.flatMap { statusCode =>
      if (process.killed || statusCode == 0) Future.successful(sb.toString)
      else Future.failed(new Exception("Status code: " + statusCode))
    }
  }
}
