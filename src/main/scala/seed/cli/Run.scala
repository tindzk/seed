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

object Run {
  def ui(
    buildPath: Path,
    seedConfig: Config,
    command: Command.Run,
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
            (WsCommand.Run(build, command.module): WsCommand).asJson.noSpaces
          }, log)
          client.connect()
        }

      case None =>
        val tmpfs    = command.packageConfig.tmpfs || seedConfig.build.tmpfs
        val progress = seedConfig.cli.progress
        run(
          buildPath,
          command.module,
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

  def run(
    buildPath: Path,
    module: String,
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
        import result.{build, projectPath}

        val parsedModule = util.Target.parseModuleString(result.build)(module)

        util.Validation.unpack(List(parsedModule)).right.flatMap { allModules =>
          val module = allModules.head

          module match {
            case util.Target.Parsed(module, None)
                if module.module.targets.length > 1 =>
              Left(List("Ambiguous platform target"))
            case util.Target.Parsed(_, Some(Right(_))) =>
              Left(List("Invalid platform target specified"))
            case _ =>
              val processes = seed.cli.BuildTarget.buildTargets(
                build,
                List(module),
                projectPath,
                watch,
                tmpfs,
                log
              )

              val runModule = module match {
                case util.Target.Parsed(module, None) =>
                  val platform = module.module.targets.head
                  Some((module.name, platform))
                case util.Target.Parsed(module, Some(Left(platform))) =>
                  Some((module.name, platform))
                case util.Target.Parsed(_, Some(Right(_))) => None
              }

              runModule match {
                case None => Right(ZIO.unit)
                case Some((module, platform)) =>
                  val expandedModules =
                    BuildConfig.expandModules(build, List(module -> platform))
                  val consoleOutput = new ConsoleOutput(log, onStdOut)

                  val client = new BloopClient(
                    consoleOutput,
                    progress,
                    projectPath,
                    build,
                    expandedModules,
                    onBuildEvent
                  )

                  val program = Bsp
                    .runBspServerAndConnect(
                      client,
                      projectPath,
                      consoleOutput.log
                    )
                    .flatMap {
                      case (bspProcess, socket, server) =>
                        val run =
                          Bsp.runModule(
                            client,
                            server,
                            build,
                            projectPath,
                            consoleOutput,
                            module,
                            platform,
                            progress
                          )

                        val program =
                          if (!watch)
                            run.ensuring(
                              Bsp.shutdown(bspProcess, socket, server)
                            )
                          else
                            Bsp.watchAction(
                              build,
                              client,
                              consoleOutput,
                              expandedModules,
                              run,
                              wait = false,
                              runInitially = true
                            )

                        Bsp.interruptIfParentFails(
                          bspProcess.fiber.join,
                          program
                        )
                    }

                  val await = processes.collect { case Left(p)  => p }
                  val async = processes.collect { case Right(p) => p }

                  if (await.nonEmpty)
                    consoleOutput.log
                      .info(
                        s"Awaiting termination of ${await.length} processes..."
                      )

                  Right(
                    for {
                      _ <- ZIO.collectAllPar(await)
                      _ <- ZIO.collectAllPar(async :+ program)
                    } yield ()
                  )
              }
          }
        }
    }
}
