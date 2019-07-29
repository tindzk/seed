package seed.cli

import java.net.URI
import java.nio.file.Path

import seed.Log
import seed.cli.util.{Ansi, RTS, WsClient}
import seed.config.BuildConfig
import seed.model
import seed.model.Config
import seed.Cli.Command
import zio._

object Build {
  def ui(
    buildPath: Path,
    seedConfig: Config,
    command: Command.Build,
    log: Log
  ): Unit =
    command.webSocket match {
      case Some(connection) =>
        if (command.watch)
          log.error("--watch cannot be combined with --connect")
        else {
          val uri = s"ws://${connection.host}:${connection.port}"
          log.debug(s"Connecting to ${Ansi.italic(uri)}...")
          val client = new WsClient(new URI(uri), { () =>
            import io.circe.syntax._
            val build =
              if (buildPath.isAbsolute) buildPath else buildPath.toAbsolutePath
            (WsCommand.Build(build, command.modules): WsCommand).asJson.noSpaces
          }, log)
          client.connect()
        }

      case None =>
        val tmpfs = command.packageConfig.tmpfs || seedConfig.build.tmpfs
        build(
          buildPath,
          None,
          command.modules,
          command.watch,
          tmpfs,
          log,
          _ => log.info
        ) match {
          case Left(errors) =>
            errors.foreach(log.error)
            sys.exit(1)
          case Right(uio) =>
            val result = RTS.unsafeRunSync(uio)
            sys.exit(if (result.succeeded) 0 else 1)
        }
    }

  def build(
    buildPath: Path,
    projectPath: Option[Path],
    modules: List[String],
    watch: Boolean,
    tmpfs: Boolean,
    log: Log,
    onStdOut: model.Build => String => Unit
  ): Either[List[String], UIO[Unit]] =
    BuildConfig.load(buildPath, log) match {
      case None => Left(List())
      case Some(
          BuildConfig.Result(build, buildProjectPath, moduleProjectPaths)
          ) =>
        val parsedModules = modules.map(util.Target.parseModuleString(build))
        util.Validation.unpack(parsedModules).right.map { allModules =>
          val processes = BuildTarget.buildTargets(
            build,
            allModules,
            projectPath.getOrElse(buildProjectPath),
            moduleProjectPaths,
            watch,
            tmpfs,
            log
          )

          val buildModules = allModules.flatMap {
            case util.Target.Parsed(module, None) =>
              BuildConfig.buildTargets(build, module.name)
            case util.Target.Parsed(module, Some(Left(platform))) =>
              List(BuildConfig.targetName(build, module.name, platform))
            case util.Target.Parsed(_, Some(Right(_))) => List()
          }

          val bloop = util.BloopCli
            .compile(
              build,
              projectPath.getOrElse(buildProjectPath),
              buildModules,
              watch,
              log,
              onStdOut(build)
            )
            .getOrElse(ZIO.unit)

          val await = processes.collect { case Left(p)  => p }
          val async = processes.collect { case Right(p) => p }

          if (await.nonEmpty)
            log.info(s"Awaiting termination of ${await.length} processes...")

          for {
            _ <- ZIO.collectAllPar(await)
            _ <- ZIO.collectAllPar(async :+ bloop)
          } yield ()
        }
    }
}
