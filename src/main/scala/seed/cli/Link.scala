package seed.cli

import java.net.URI
import java.nio.file.Path

import seed.Log
import seed.cli.util.{Ansi, ConsoleOutput, RTS, WsClient}
import seed.config.BuildConfig
import seed.model.{BuildEvent, Config, Platform}
import seed.Cli.Command
import seed.build.{BloopClient, BloopServer, Bsp}
import seed.config.BuildConfig.Build
import zio._

object Link {
  def ui(
    buildPath: Path,
    seedConfig: Config,
    command: Command.Link,
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
            (WsCommand
              .Link(build, command.modules, command.optimise): WsCommand).asJson.noSpaces
          }, log)
          client.connect()
        }

      case None =>
        val tmpfs    = command.packageConfig.tmpfs || seedConfig.build.tmpfs
        val progress = seedConfig.cli.progress
        link(
          buildPath,
          command.modules,
          command.optimise,
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

  def link(
    buildPath: Path,
    modules: List[String],
    optimise: Boolean,
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

        val parsedModules =
          modules.map(util.Target.parseModuleString(result.build))
        util.Validation.unpack(parsedModules).right.map { allModules =>
          val consoleOutput = new ConsoleOutput(log, onStdOut)

          val processes = seed.cli.BuildTarget.buildTargets(
            build,
            allModules,
            projectPath,
            watch,
            tmpfs,
            consoleOutput.log
          )

          val linkModules = allModules.flatMap {
            case util.Target.Parsed(module, None) =>
              BuildConfig.linkTargets(build, module.name)
            case util.Target.Parsed(module, Some(Left(platform))) =>
              List((module.name, platform))
            case util.Target.Parsed(_, Some(Right(_))) => List()
          }

          val expandedModules = BuildConfig.expandModules(build, linkModules)

          val client = new BloopClient(
            consoleOutput,
            progress,
            projectPath,
            build,
            expandedModules,
            onBuildEvent
          )

          val program = Bsp
            .runBspServerAndConnect(client, projectPath, consoleOutput.log)
            .flatMap {
              case (bspProcess, socket, server) =>
                val l = linkPass(
                  consoleOutput,
                  client,
                  server,
                  build,
                  projectPath,
                  expandedModules,
                  linkModules,
                  optimise,
                  progress,
                  consoleOutput.log,
                  onBuildEvent
                )

                val program =
                  if (!watch)
                    l.ensuring(Bsp.shutdown(bspProcess, socket, server))
                  else
                    Bsp.watchAction(
                      build,
                      client,
                      consoleOutput,
                      expandedModules,
                      l,
                      wait = true,
                      runInitially = true
                    )

                Bsp.interruptIfParentFails(bspProcess.fiber.join, program)
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

  def linkPass(
    consoleOutput: ConsoleOutput,
    client: BloopClient,
    server: BloopServer,
    build: Build,
    projectPath: Path,
    expandModules: List[(String, Platform)],
    linkModules: List[(String, Platform)],
    optimise: Boolean,
    progress: Boolean,
    log: Log,
    onBuildEvent: BuildEvent => Unit
  ): UIO[Unit] =
    if (linkModules.isEmpty) ZIO.unit
    else
      for {
        _ <- Bsp.compile(
          client,
          server,
          consoleOutput,
          progress,
          build,
          projectPath,
          expandModules
        )
        _ <- util.BloopCli
          .link(
            projectPath,
            linkModules.map {
              case (m, p) => BuildConfig.targetName(build, m, p)
            },
            optimise,
            log,
            line => consoleOutput.writeRegular(line + "\n"),
            onBuildEvent
          )
      } yield ()
}
