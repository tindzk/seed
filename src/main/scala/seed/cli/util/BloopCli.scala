package seed.cli.util

import java.nio.file.Path

import seed.Log
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
}
