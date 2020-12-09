package seed.cli.util

import java.nio.file.Path

import seed.{Log, LogLevel}
import seed.config.BuildConfig.Build
import seed.process.ProcessHelper
import seed.model.BuildEvent
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.Platform

object BloopCli {

  def parseBloopModule(
    build: Build,
    bloopName: String
  ): (String, Platform) =
    if (bloopName.endsWith("-js"))
      (bloopName.dropRight("-js".length), JavaScript)
    else if (bloopName.endsWith("-jvm"))
      (bloopName.dropRight("-jvm".length), JVM)
    else if (bloopName.endsWith("-native"))
      (bloopName.dropRight("-native".length), Native)
    else {
      require(
        build(bloopName).module.targets.length == 1,
        "Only one target expected"
      )
      (bloopName, build(bloopName).module.targets.head)
    }

  def link(
    projectPath: Path,
    bloopModules: List[String],
    optimise: Boolean,
    log: Log,
    onStdOut: String => Unit,
    onBuildEvent: BuildEvent => Unit
  ): ProcessHelper.Process = {
    val optimiseParam = if (!optimise) List() else List("--optimize", "release")
    val args          = List("link") ++ bloopModules ++ optimiseParam

    def f(message: String): Unit = {
      onStdOut(message)

      val parts = message.split(" ")
      if (parts.headOption.contains("Generated")) {
        val offset = message.indexOf('\'')
        val path   = message.substring(offset + 1).init
        onBuildEvent(BuildEvent.Linked(path))
      }
    }

    ProcessHelper.runBloop(projectPath, log, f, verbose = false)(args: _*)
  }

  def wrapLog(log: Log): Log =
    new Log(log.f, log.map, log.level, log.unicode) {
      var lastLogLevel: Option[LogLevel] = None

      override def error(message: String, detail: Boolean = false): Unit = {
        // Remove log level ("[E] ") from message since it contains escape
        // codes. Furthermore, the tab character is replaced by spaces.
        // These fixes are needed to prevent graphical glitches when the
        // progress bars are updated.
        val messageText =
          (if (message.contains(' ')) message.dropWhile(_ != ' ').tail
           else message).replaceAllLiterally("\t", "  ")

        if (messageText.trim.nonEmpty) {
          // TODO The BSP server should not indicate the log level in the message
          val logLevel = (if (message.contains("[D]")) Some(LogLevel.Debug)
                          else if (message.contains("[E]"))
                            Some(LogLevel.Error)
                          else lastLogLevel).getOrElse(LogLevel.Info)
          lastLogLevel = Some(logLevel)

          // This message is printed to stderr, but it is not an error,
          // therefore change the log level to 'debug'
          if (messageText.contains("BSP server cancelled, closing socket..."))
            super.debug(messageText)
          else {
            if (logLevel == LogLevel.Debug) super.debug(messageText, detail)
            else if (logLevel == LogLevel.Error)
              super.error(messageText, detail)
            else super.info(messageText, detail)
          }
        }
      }
    }
}
