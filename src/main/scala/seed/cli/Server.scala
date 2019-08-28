package seed.cli

import java.net.InetSocketAddress
import java.nio.file.{Path, Paths}

import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import org.java_websocket.WebSocket
import seed.Log
import seed.cli.util.{RTS, WsServer}
import seed.Cli.Command
import seed.model.{BuildEvent, Config}
import zio.{Fiber, UIO}

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed abstract class WsCommand(val description: String)
object WsCommand {
  case class Run(build: Path, module: String) extends WsCommand("Run")
  case class Link(build: Path, modules: List[String], optimise: Boolean = false)
      extends WsCommand("Link")
  case class Build(build: Path, targets: List[String])
      extends WsCommand("Build")
  case object BuildEvents extends WsCommand("Build events")

  implicit val decodeRun: Decoder[Run] = json =>
    for {
      build   <- json.downField("build").as[String].map(Paths.get(_))
      modules <- json.downField("module").as[String]
    } yield Run(build, modules)

  val encodeRun: Run => List[(String, Json)] = run =>
    List(
      "build"  -> Json.fromString(run.build.toString),
      "module" -> implicitly[Encoder[String]].apply(run.module)
    )

  implicit val decodeLink: Decoder[Link] = json =>
    for {
      build    <- json.downField("build").as[String].map(Paths.get(_))
      modules  <- json.downField("modules").as[List[String]]
      optimise <- json.downField("optimise").as[Option[Boolean]]
    } yield Link(build, modules, optimise.getOrElse(false))

  val encodeLink: Link => List[(String, Json)] = link =>
    List(
      "build"    -> Json.fromString(link.build.toString),
      "modules"  -> implicitly[Encoder[List[String]]].apply(link.modules),
      "optimise" -> implicitly[Encoder[Boolean]].apply(link.optimise)
    )

  implicit val decodeBuild: Decoder[Build] = json =>
    for {
      build   <- json.downField("build").as[String].map(Paths.get(_))
      targets <- json.downField("targets").as[List[String]]
    } yield Build(build, targets)

  val encodeBuild: Build => List[(String, Json)] = build =>
    List(
      "build"   -> Json.fromString(build.build.toString),
      "targets" -> implicitly[Encoder[List[String]]].apply(build.targets)
    )

  implicit val decodeCommand: Decoder[WsCommand] = json =>
    for {
      commandName <- json.downField("command").as[String]
      command <- if (commandName == "run") json.as[Run]
      else if (commandName == "link") json.as[Link]
      else if (commandName == "build") json.as[Build]
      else if (commandName == "buildEvents") Right(BuildEvents)
      else Left(DecodingFailure(s"Invalid command: $commandName", json.history))
    } yield command

  implicit val encodeCommand: Encoder[WsCommand] = {
    case build: WsCommand.Build =>
      Json.fromFields(
        List("command" -> Json.fromString("build")) ++ encodeBuild(build)
      )
    case run: WsCommand.Run =>
      Json.fromFields(
        List("command" -> Json.fromString("run")) ++ encodeRun(run)
      )
    case link: WsCommand.Link =>
      Json.fromFields(
        List("command" -> Json.fromString("link")) ++ encodeLink(link)
      )
    case BuildEvents =>
      Json.fromFields(List("command" -> Json.fromString("buildEvents")))
  }
}

object Server {
  private val fibers            = mutable.HashMap[WebSocket, Fiber[Nothing, Unit]]()
  private val buildEventClients = mutable.HashSet[WebSocket]()

  def ui(config: Config, command: Command.Server, log: Log): Unit = {
    val wsConfig = command.webSocket
    val webSocket = new WsServer(
      new InetSocketAddress(wsConfig.host, wsConfig.port),
      onDisconnect(log),
      evalCommand(config, log),
      log
    )
    webSocket.start()
  }

  def onStdOut(wsClient: WebSocket)(message: String): Unit =
    if (wsClient.isOpen) wsClient.send(message)

  def onBroadcastBuildEvent(wsServer: WsServer, serverLog: Log)(
    event: BuildEvent
  ): Unit =
    if (buildEventClients.nonEmpty) {
      serverLog.debug(
        s"Broadcasting event to ${buildEventClients.size} clients..."
      )
      wsServer.broadcast(
        event.asJson.noSpaces + "\n",
        buildEventClients.toList.asJava
      )
    }

  def runCommandUio(uio: UIO[Unit], wsClient: WebSocket): Unit = {
    val program = for {
      fiber <- uio.fork
      _ = fibers += wsClient -> fiber
      _ <- fiber.await // TODO Send success state to client
      _ <- UIO(fibers -= wsClient)
      _ <- UIO(wsClient.close())
    } yield ()

    RTS.unsafeRunAsync(program)(_ => ())
  }

  def onDisconnect(log: Log)(wsClient: WebSocket): Unit = {
    fibers.get(wsClient).foreach { fiber =>
      RTS.unsafeRunAsync(fiber.interrupt)(_ => log.info("Interrupted command"))
      fibers -= wsClient
    }

    buildEventClients -= wsClient
  }

  def evalCommand(config: Config, serverLog: Log)(
    wsServer: WsServer,
    wsClient: WebSocket,
    command: WsCommand
  ): Unit = {
    import config.build.tmpfs
    import config.cli.progress

    val clientLog = new Log(
      message => if (wsClient.isOpen) wsClient.send(message + "\n"),
      identity,
      serverLog.level,
      serverLog.unicode
    )
    command match {
      case WsCommand.BuildEvents => buildEventClients += wsClient
      case WsCommand.Build(buildPath, targets) =>
        seed.cli.Build.build(
          buildPath,
          None,
          targets,
          watch = false,
          tmpfs,
          progress,
          clientLog,
          onStdOut(wsClient),
          onBroadcastBuildEvent(wsServer, serverLog)
        ) match {
          case Left(errors) =>
            errors.foreach(clientLog.error)
            wsClient.close()
          case Right(uio) => runCommandUio(uio, wsClient)
        }
      case WsCommand.Run(buildPath, modules) =>
        seed.cli.Run.run(
          buildPath,
          modules,
          watch = false,
          tmpfs,
          progress,
          clientLog,
          onStdOut(wsClient),
          onBroadcastBuildEvent(wsServer, serverLog)
        ) match {
          case Left(errors) =>
            errors.foreach(clientLog.error)
            wsClient.close()
          case Right(uio) => runCommandUio(uio, wsClient)
        }
      case WsCommand.Link(buildPath, modules, optimise) =>
        seed.cli.Link.link(
          buildPath,
          modules,
          optimise = optimise,
          watch = false,
          tmpfs,
          progress,
          clientLog,
          onStdOut(wsClient),
          onBroadcastBuildEvent(wsServer, serverLog)
        ) match {
          case Left(errors) =>
            errors.foreach(clientLog.error)
            wsClient.close()
          case Right(uio) => runCommandUio(uio, wsClient)
        }
    }
  }
}
