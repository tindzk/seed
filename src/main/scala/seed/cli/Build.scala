package seed.cli

import java.net.URI
import java.nio.file.Path

import seed.Log
import seed.cli.util.{Ansi, ConsoleOutput, RTS, WsClient}
import seed.config.BuildConfig
import seed.model.{BuildEvent, Config}
import seed.Cli.Command
import seed.build.{BloopClient, Bsp}
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
        val tmpfs    = command.packageConfig.tmpfs || seedConfig.build.tmpfs
        val progress = seedConfig.cli.progress
        build(
          buildPath,
          None,
          command.modules,
          command.watch,
          tmpfs,
          progress,
          log,
          print,
          _ => ()
        ) match {
          case Left(errors) =>
            errors.foreach(log.error(_))
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
    progress: Boolean,
    log: Log,
    onStdOut: String => Unit,
    onBuildEvent: BuildEvent => Unit
  ): Either[List[String], UIO[Unit]] =
    BuildConfig.load(buildPath, log) match {
      case None => Left(List())
      case Some(result) =>
        import result.{projectPath => buildProjectPath}
        val build = result.build

        val parsedModules = modules.map(util.Target.parseModuleString(build))
        util.Validation.unpack(parsedModules).right.map { allModules =>
          val path = projectPath.getOrElse(buildProjectPath)

          val processes = BuildTarget.buildTargets(
            build,
            allModules,
            path,
            watch,
            tmpfs,
            log
          )

          val buildModules = allModules.flatMap {
            case util.Target.Parsed(module, None) =>
              build(module.name).module.targets.map((module.name, _))
            case util.Target.Parsed(module, Some(Left(platform))) =>
              List((module.name, platform))
            case util.Target.Parsed(_, Some(Right(_))) => List()
          }

          val expandedModules =
            BuildConfig.expandModules(build, buildModules)

          val consoleOutput = new ConsoleOutput(log, onStdOut)

          val program =
            if (expandedModules.isEmpty) UIO(())
            else {
              val client = new BloopClient(
                consoleOutput,
                progress,
                path,
                build,
                expandedModules,
                onBuildEvent
              )

              Bsp
                .runBspServerAndConnect(client, path, consoleOutput.log)
                .flatMap {
                  case (bspProcess, socket, server) =>
                    val classDirectories = Bsp.classDirectories(
                      server,
                      build,
                      path,
                      expandedModules
                    )

                    val compile =
                      Bsp.compile(
                        client,
                        server,
                        consoleOutput,
                        progress,
                        build,
                        path,
                        expandedModules
                      )

                    val program =
                      for {
                        moduleDirs <- classDirectories.either
                        _ = {
                          moduleDirs.right.get.foreach {
                            case (module, dir) =>
                              consoleOutput.log.debug(
                                s"Module ${Ansi.italic(module._1)}: ${Ansi.italic(dir)}"
                              )
                          }
                        }
                        _ <- if (!watch)
                          compile
                            .ensuring(Bsp.shutdown(bspProcess, socket, server))
                        else
                          Bsp.watchAction(
                            build,
                            client,
                            consoleOutput,
                            expandedModules,
                            compile,
                            wait = true,
                            runInitially = true
                          )
                      } yield ()

                    Bsp.interruptIfParentFails(bspProcess.fiber.join, program)
                }
            }

          val await = processes.collect { case Left(p)  => p }
          val async = processes.collect { case Right(p) => p }

          if (await.nonEmpty)
            consoleOutput.log.info(
              s"Awaiting termination of ${await.length} processes..."
            )

          for {
            _ <- ZIO.collectAllPar(await)
            _ <- ZIO.collectAllPar(async :+ program)
          } yield ()
        }
    }
}
