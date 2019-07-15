package seed.cli

import java.net.URI
import java.nio.file.Path

import seed.Log
import seed.cli.util.WsClient
import seed.config.BuildConfig
import seed.model
import seed.model.Config
import seed.Cli.Command

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

import scala.concurrent.ExecutionContext.Implicits.global

object Link {
  def ui(buildPath: Path, seedConfig: Config, command: Command.Link): Unit = {
    command.webSocket match {
      case Some(connection) =>
        if (command.watch)
          Log.error("--watch cannot be combined with --connect")
        else {
          val uri = s"ws://${connection.host}:${connection.port}"
          Log.debug(s"Connecting to $uri...")
          val client = new WsClient(new URI(uri), { () =>
            import io.circe.syntax._
            val build =
              if (buildPath.isAbsolute) buildPath else buildPath.toAbsolutePath
            (WsCommand.Link(build, command.modules): WsCommand).asJson.noSpaces
          })
          client.connect()
        }

      case None =>
        val tmpfs = command.packageConfig.tmpfs || seedConfig.build.tmpfs
        link(buildPath, command.modules, command.watch, tmpfs, Log, _ => println) match {
          case Left(errors) =>
            errors.foreach(Log.error)
            sys.exit(1)
          case Right(future) => Await.result(future, Duration.Inf)
        }
    }
  }

  def link(buildPath: Path,
           modules: List[String],
           watch: Boolean,
           tmpfs: Boolean,
           log: Log,
           onStdOut: model.Build => String => Unit
          ): Either[List[String], Future[Unit]] =
    BuildConfig.load(buildPath, log) match {
      case None => Left(List())
      case Some(BuildConfig.Result(build, projectPath, moduleProjectPaths)) =>
        val parsedModules = modules.map(util.Target.parseModuleString(build))
        util.Validation.unpack(parsedModules).right.map { allModules =>
          val futures = BuildTarget.buildTargets(build, allModules, projectPath,
            moduleProjectPaths, watch, tmpfs, log)

          val linkModules = allModules.flatMap {
            case util.Target.Parsed(module, None) =>
              BuildConfig.linkTargets(build, module.name)
            case util.Target.Parsed(module, Some(Left(platform))) =>
              List(BuildConfig.targetName(build, module.name, platform))
            case util.Target.Parsed(_, Some(Right(_))) => List()
          }

          val bloop = util.BloopCli.link(
            build, projectPath, linkModules, watch, log, onStdOut(build)
          ).fold(Future.unit)(_.success)

          Future.sequence(futures :+ bloop).map(_ => ())
        }
    }
}
