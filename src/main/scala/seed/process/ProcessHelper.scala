package seed.process

import java.nio.ByteBuffer
import java.nio.file.Path

import com.zaxxer.nuprocess.{
  NuAbstractProcessHandler,
  NuProcess,
  NuProcessBuilder
}
import seed.Log

import scala.collection.JavaConverters._
import seed.cli.util.Ansi
import zio._

sealed trait ProcessOutput
object ProcessOutput {
  case class StdErr(output: String) extends ProcessOutput
  case class StdOut(output: String) extends ProcessOutput
}

/**
  * @param onProcStart  Takes PID
  * @param onProcExit   Takes exit code
  */
class ProcessHandler(
  onLog: ProcessOutput => Unit,
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
      new String(bytes)
        .split("\n")
        .foreach(line => onLog(ProcessOutput.StdErr(line)))
    }

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
    if (!closed) {
      val bytes = new Array[Byte](buffer.remaining)
      buffer.get(bytes)
      new String(bytes)
        .split("\n")
        .foreach(line => onLog(ProcessOutput.StdOut(line)))
    }
}

object ProcessHelper {

  /**
    * UIO type that wraps a NuProcess instance and completes when the process
    * returned the exit code 0, otherwise it fails.
    */
  type Process = UIO[Unit]

  def runCommand(
    cwd: Path,
    cmd: List[String],
    modulePath: Option[String] = None,
    buildPath: Option[String] = None,
    log: Log,
    onStdOut: String => Unit,
    verbose: Boolean = true
  ): Process =
    UIO.effectAsyncInterrupt { termination =>
      log.info(s"Running command '${Ansi.italic(cmd.mkString(" "))}'...")
      log.debug(
        s"Working directory: ${Ansi.italic(cwd.toAbsolutePath.toString)}",
        detail = true
      )

      val pb = new NuProcessBuilder(cmd.asJava)

      modulePath.foreach { mp =>
        pb.environment().put("MODULE_PATH", mp)
        log.debug(s"Module path: ${Ansi.italic(mp)}", detail = true)
      }

      buildPath.foreach { bp =>
        pb.environment().put("BUILD_PATH", bp)
        log.debug(s"Build path: ${Ansi.italic(bp)}", detail = true)
      }

      var destroyed = false

      pb.setProcessListener(
        new ProcessHandler(
          {
            case ProcessOutput.StdOut(output) => onStdOut(output)
            case ProcessOutput.StdErr(output) => log.error(output)
          },
          pid => if (verbose) log.debug("PID: " + pid, detail = true),
          code =>
            if (!destroyed) {
              if (code == 0) {
                if (verbose) log.debug(s"Process terminated successfully")
                termination(Task.succeed(()))
              } else {
                log.error(s"Process exited with non-zero exit code $code")
                termination(Task.interrupt)
              }
            }
        )
      )

      if (cwd.toString != "") pb.setCwd(cwd)

      val process = pb.start()
      Left(UIO(if (process != null) {
        destroyed = true
        process.destroy(false)
      }))
    }

  def runBloop(
    cwd: Path,
    log: Log,
    onStdOut: String => Unit,
    modulePath: Option[String] = None,
    buildPath: Option[String] = None,
    verbose: Boolean = true
  )(args: String*): Process =
    runCommand(
      cwd,
      List("bloop") ++ args,
      modulePath,
      buildPath,
      log,
      output => onStdOut(output),
      verbose
    )

  def runShell(
    cwd: Path,
    command: String,
    buildPath: String,
    log: Log,
    onStdOut: String => Unit
  ): Process =
    runCommand(
      cwd,
      List("/bin/sh", "-c", command),
      None,
      Some(buildPath),
      log,
      onStdOut
    )
}
