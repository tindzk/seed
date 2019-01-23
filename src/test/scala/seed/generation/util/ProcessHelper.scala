package seed.generation.util

import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.{Executors, TimeUnit}

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import seed.Log

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}

sealed trait ProcessOutput
object ProcessOutput {
  case class StdErr(output: String) extends ProcessOutput
  case class StdOut(output: String) extends ProcessOutput
}

/**
  * @param onProcStart  Takes PID
  * @param onProcExit   Takes exit code
  */
class ProcessHandler(onLog: ProcessOutput => Unit,
                     onProcStart: Int => Unit,
                     onProcExit: Int => Unit
                    ) extends NuAbstractProcessHandler {
  override def onStart(nuProcess: NuProcess): Unit =
    onProcStart(nuProcess.getPID)

  override def onExit(statusCode: Int): Unit = onProcExit(statusCode)

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)
      new String(bytes).split("\n").foreach(line =>
        onLog(ProcessOutput.StdErr(line)))
    }

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)
      new String(bytes).split("\n").foreach(line =>
        onLog(ProcessOutput.StdOut(line)))
    }
}

object ProcessHelper {
  val scheduler = Executors.newScheduledThreadPool(1)
  def schedule(seconds: Int)(f: => Unit): Unit =
    scheduler.schedule({ () => f }: Runnable, seconds, TimeUnit.SECONDS)

  def runBloop(cwd: Path, silent: Boolean = false)
              (args: String*): Future[String] = {
    val promise = Promise[String]()
    val sb = new StringBuilder
    val pb = new NuProcessBuilder((List("bloop") ++ args).asJava)

    var terminated = false

    pb.setProcessListener(new ProcessHandler(
      { case ProcessOutput.StdOut(output) =>
          if (!silent) Log.info("stdout: " + output)
          sb.append(output + "\n")
        case ProcessOutput.StdErr(output) =>
          if (!silent) Log.error("stderr: " + output)
          sb.append(output + "\n")
      },
      pid => if (!silent) Log.info("PID: " + pid) else (),
      { statusCode =>
        if (!silent) Log.info("Status code: " + statusCode)
        if (terminated || statusCode == 0) promise.success(sb.toString)
        else promise.failure(new Exception("Status code: " + statusCode))
      }
    ))
    pb.setCwd(cwd)
    val proc = pb.start()

    // Work around a CI problem where onExit() does not get called on
    // ProcessHandler
    schedule(60) {
      if (!promise.isCompleted) {
        Log.error(s"Process did not terminate after 60s (isRunning = ${proc.isRunning})")
        if (proc.isRunning) {
          Log.error("Forcing termination...")
          terminated = true
          proc.destroy(true)
        }
      }
    }

    promise.future
  }
}
