package seed.process

import java.nio.ByteBuffer
import java.nio.file.Path

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import seed.Log
import seed.cli.util.{Ansi, BloopCli}

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
  /**
    * @param nuProcess   Underlying NuProcess instance
    * @param termination Future that terminates with status code
    */
  class Process(private val nuProcess: NuProcess,
                val termination: Future[Int]) {
    private var _killed = false

    def isRunning: Boolean = nuProcess.isRunning
    def killed: Boolean = _killed
    def kill(): Unit = {
      nuProcess.destroy(true)
      _killed = true
    }
  }

  def runBloop(cwd: Path,
               silent: Boolean = false,
               onStdOut: String => Unit
              )(args: String*): Process = {
    val cmd = List("bloop") ++ args
    Log.info(s"Running ${Ansi.italic(cmd.mkString(" "))} in ${Ansi.italic(cwd.toString)}...")

    val termination = Promise[Int]()

    val pb = new NuProcessBuilder(cmd.asJava)
    pb.setProcessListener(new ProcessHandler(
      { case ProcessOutput.StdOut(output) =>
        if (!silent) System.out.println(output)
        if (!BloopCli.skipOutput(output)) onStdOut(output)
      case ProcessOutput.StdErr(output) =>
        if (!silent) System.err.println(output)
        if (!BloopCli.skipOutput(output)) onStdOut(output)
      },
      pid => if (!silent) Log.info("PID: " + pid) else (),
      { statusCode =>
        if (!silent) Log.info("Status code: " + statusCode)
        termination.success(statusCode)
      }
    ))
    if (cwd.toString != "") pb.setCwd(cwd)
    new Process(pb.start(), termination.future)
  }
}
