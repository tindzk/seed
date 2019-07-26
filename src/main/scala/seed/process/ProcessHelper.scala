package seed.process

import java.nio.ByteBuffer
import java.nio.file.Path

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import seed.Log

import scala.collection.JavaConverters._
import seed.cli.util.{Ansi, BloopCli, RTS}
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
    * UIO type that wraps a NuProcess instance and completes when the process
    * returned the exit code 0, otherwise it fails.
    */
  type Process = UIO[Unit]

  def runCommmand(cwd: Path,
                  cmd: List[String],
                  modulePath: Option[String] = None,
                  buildPath: Option[String] = None,
                  log: Log,
                  onStdOut: String => Unit
                 ): Process =
    Promise.make[Nothing, Unit].flatMap { termination =>
      log.info(s"Running command '${Ansi.italic(cmd.mkString(" "))}'...")
      log.detail(s"Working directory: ${Ansi.italic(cwd.toString)}")

      val pb = new NuProcessBuilder(cmd.asJava)

      modulePath.foreach { mp =>
        pb.environment().put("MODULE_PATH", mp)
        log.detail(s"Module path: ${Ansi.italic(mp)}")
      }

      buildPath.foreach { bp =>
        pb.environment().put("BUILD_PATH", bp)
        log.detail(s"Build path: ${Ansi.italic(bp)}")
      }

      pb.setProcessListener(new ProcessHandler(
        {
          case ProcessOutput.StdOut(output) => onStdOut(output)
          case ProcessOutput.StdErr(output) => log.error(output)
        },
        pid => log.debug("PID: " + pid),
        code => {
          log.debug("Exit code: " + code)
          if (code == 0) RTS.unsafeRun(termination.succeed(()))
          else {
            log.error(s"Process exited with non-zero exit code")
            RTS.unsafeRun(termination.interrupt)
          }
        }))

      if (cwd.toString != "") pb.setCwd(cwd)

      pb.start()
      termination.await
    }

  def runBloop(cwd: Path,
               log: Log,
               onStdOut: String => Unit,
               modulePath: Option[String] = None,
               buildPath: Option[String] = None
              )(args: String*): Process =
    runCommmand(cwd, List("bloop") ++ args, modulePath, buildPath,
      log, output => if (!BloopCli.skipOutput(output)) onStdOut(output))

  def runShell(cwd: Path,
               command: String,
               buildPath: String,
               log: Log,
               onStdOut: String => Unit
              ): Process =
    runCommmand(cwd, List("/bin/sh", "-c", command), None, Some(buildPath), log,
      onStdOut)
}
