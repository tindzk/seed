package seed.cli

import java.net.InetSocketAddress
import java.nio.file.{Path, Paths}

import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import org.java_websocket.WebSocket
import seed.Log
import seed.cli.util.{Ansi, BloopCli, WsServer}
import seed.model
import seed.Cli.Command
import seed.model.Config

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._

sealed abstract class WsCommand(val description: String)
object WsCommand {
  case class Link(build: Path, modules: List[String]) extends WsCommand("Link")
  case class Build(build: Path, targets: List[String]) extends WsCommand("Build")
  case object BuildEvents extends WsCommand("Build events")

  implicit val decodeLink: Decoder[Link] = json =>
    for {
      build    <- json.downField("build").as[String].map(Paths.get(_))
      modules  <- json.downField("modules").as[List[String]]
    } yield Link(build, modules)

  val encodeLink: Link => List[(String, Json)] = link =>
    List(
      "build" -> Json.fromString(link.build.toString),
      "modules" -> implicitly[Encoder[List[String]]].apply(link.modules))

  implicit val decodeBuild: Decoder[Build] = json =>
    for {
      build   <- json.downField("build").as[String].map(Paths.get(_))
      targets <- json.downField("targets").as[List[String]]
    } yield Build(build, targets)

  val encodeBuild: Build => List[(String, Json)] = build =>
    List(
      "build" -> Json.fromString(build.build.toString),
      "targets" -> implicitly[Encoder[List[String]]].apply(build.targets))

  implicit val decodeCommand: Decoder[WsCommand] = json =>
    for {
      commandName <- json.downField("command").as[String]
      command <-
        if (commandName == "link") json.as[Link]
        else if (commandName == "build") json.as[Build]
        else if (commandName == "buildEvents") Right(BuildEvents)
        else Left(DecodingFailure(s"Invalid command: $commandName", json.history))
    } yield command

  implicit val encodeCommand: Encoder[WsCommand] = {
    case build: WsCommand.Build =>
      Json.fromFields(
        List("command" -> Json.fromString("build")) ++ encodeBuild(build))
    case link: WsCommand.Link =>
      Json.fromFields(
        List("command" -> Json.fromString("link")) ++ encodeLink(link))
    case BuildEvents =>
      Json.fromFields(List("command" -> Json.fromString("buildEvents")))
  }
}

object Server {
  private val buildEventClients = mutable.HashSet[WebSocket]()

  def ui(config: Config, command: Command.Server, log: Log): Unit = {
    val wsConfig = command.webSocket
    val webSocket = new WsServer(
      new InetSocketAddress(wsConfig.host, wsConfig.port), onDisconnect,
      evalCommand(config, log), log)
    webSocket.start()
  }

  def onStdOut(wsServer: WsServer, wsClient: WebSocket, build: model.Build,
               serverLog: Log
              )(message: String): Unit = {
    wsClient.send(message)

    if (buildEventClients.nonEmpty) {
      val event = BloopCli.parseStdOut(build)(message)
      event.foreach { ev =>
        serverLog.debug(
          s"Broadcasting event to ${buildEventClients.size} clients...")
        wsServer.broadcast(ev.asJson.noSpaces, buildEventClients.toList.asJava)
      }
    }
  }

  def onDisconnect(wsClient: WebSocket): Unit = buildEventClients -= wsClient

  def evalCommand(config: Config, serverLog: Log)(
    wsServer: WsServer, wsClient: WebSocket, command: WsCommand
  ): Unit = {
    import config.build.tmpfs

    val clientLog = new Log(
      wsClient.send, identity, serverLog.level, serverLog.unicode)
    command match {
      case WsCommand.BuildEvents => buildEventClients += wsClient
      case WsCommand.Build(buildPath, targets) =>
        seed.cli.Build.build(
          buildPath, targets, watch = false, tmpfs, clientLog,
          build => onStdOut(wsServer, wsClient, build, serverLog)
        ) match {
          case Left(errors) =>
            errors.foreach(clientLog.error)
            wsClient.close()
          case Right(future) => future.foreach(_ => wsClient.close())
        }
      case WsCommand.Link(buildPath, modules) =>
        seed.cli.Link.link(
          buildPath, modules, watch = false, tmpfs, clientLog,
          build => onStdOut(wsServer, wsClient, build, serverLog)
        ) match {
          case Left(errors) =>
            errors.foreach(clientLog.error)
            wsClient.close()
          case Right(future) => future.foreach(_ => wsClient.close())
        }
    }
  }
}
