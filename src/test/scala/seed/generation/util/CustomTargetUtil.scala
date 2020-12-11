package seed.generation.util

import java.nio.file.{Files, Paths}

import seed.Cli.Command
import seed.{Log, LogLevel, cli}
import seed.config.BuildConfig
import seed.generation.util.BuildUtil.tempPath
import seed.model.Config
import seed.util.TestUtil

import scala.collection.mutable.ListBuffer

object CustomTargetUtil {
  def buildCustomTarget(
    name: String,
    module: String,
    testId: String
  ): (BuildConfig.Result, () => List[String], zio.UIO[Unit]) = {
    val path = Paths.get("test", name)

    val config = BuildConfig.load(path, Log.urgent).get
    import config._
    val buildPath = tempPath.resolve(testId).resolve(name)
    Files.createDirectories(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(TestUtil.packageConfig),
      Log.urgent
    )

    val lines = ListBuffer[String]()

    val result = seed.cli.Build.build(
      path,
      Some(buildPath),
      List(module),
      watch = false,
      tmpfs = false,
      progress = false,
      new Log(lines += _, identity, LogLevel.Warn, false),
      stdOut => lines ++= stdOut.split('\n'),
      _ => ()
    )

    val uio = result.right.get
    (config, () => lines.toList, uio)
  }
}
