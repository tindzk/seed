package seed.cli

import java.net.InetSocketAddress
import java.nio.file.{Path, Paths}

import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import org.java_websocket.WebSocket
import seed.Log
import seed.cli.util.{BloopCli, WsServer}
import seed.config.BuildConfig
import seed.model
import seed.model.Platform
import seed.Cli.Command

import scala.collection.JavaConverters._
import scala.collection.mutable

import scala.concurrent.ExecutionContext.Implicits._

sealed trait WsCommand
object WsCommand {
  case class Module(name: String, platform: Option[Platform])
  case class Link(build: Path, modules: List[Module]) extends WsCommand
  case object BuildEvents extends WsCommand

  implicit val decodeModule: Decoder[Module] = json =>
    for {
      name     <- json.downField("name").as[String]
      platform <- json.downField("platform").as[Option[String]]
    } yield Module(
      name,
      platform.flatMap(p => Platform.All.keys.find(_.id == p)))

  implicit val encodeModule: Encoder[Module] = module =>
    Json.fromFields(List(
      "name" -> Json.fromString(module.name),
      "platform" ->
        implicitly[Encoder[Option[String]]].apply(module.platform.map(_.id))))


  implicit val decodeLink: Decoder[Link] = json =>
    for {
      build    <- json.downField("build").as[String].map(Paths.get(_))
      modules  <- json.downField("modules").as[List[Module]]
    } yield Link(build, modules)

  val encodeLink: Link => List[(String, Json)] = link =>
    List(
      "build" -> Json.fromString(link.build.toString),
      "modules" -> implicitly[Encoder[List[Module]]].apply(link.modules))

  implicit val decodeCommand: Decoder[WsCommand] = json =>
    for {
      commandName <- json.downField("command").as[String]
      command <-
        if (commandName == "link") json.as[Link]
        else if (commandName == "buildEvents") Right(BuildEvents)
        else Left(DecodingFailure(s"Invalid command: $commandName", json.history))
    } yield command

  implicit val encodeCommand: Encoder[WsCommand] = {
    case link: WsCommand.Link =>
      Json.fromFields(
        List("command" -> Json.fromString("link")) ++ encodeLink(link))
    case BuildEvents =>
      Json.fromFields(List("command" -> Json.fromString("buildEvents")))
  }
}

object Server {
  private val buildEventClients = mutable.HashSet[WebSocket]()

  def ui(command: Command.Server): Unit = {
    val wsConfig = command.webSocket
    val webSocket = new WsServer(
      new InetSocketAddress(wsConfig.host, wsConfig.port), onDisconnect,
      evalCommand)
    webSocket.start()
    Log.info(s"WebSocket server started on ${wsConfig.host}:${wsConfig.port}")
  }

  def onStdOut(wsServer: WsServer, wsClient: WebSocket, build: model.Build)
              (message: String): Unit = {
    wsClient.send(message)

    if (buildEventClients.nonEmpty) {
      val event = BloopCli.parseStdOut(build)(message)
      event.foreach { ev =>
        Log.debug(s"Broadcasting event to ${buildEventClients.size} clients...")
        wsServer.broadcast(ev.asJson.noSpaces, buildEventClients.toList.asJava)
      }
    }
  }

  def onDisconnect(wsClient: WebSocket): Unit = buildEventClients -= wsClient

  def evalCommand(wsServer: WsServer,
                  wsClient: WebSocket,
                  command: WsCommand
                 ): Unit = {
    val log = new Log(wsClient.send)
    command match {
      case WsCommand.BuildEvents => buildEventClients += wsClient
      case WsCommand.Link(buildPath, modules) =>
        BuildConfig.load(buildPath, log).foreach { case (projectPath, build) =>
          seed.build.Link.link(build, projectPath,
            modules.map(m => m.name -> m.platform), watch = false,
            log, onStdOut(wsServer, wsClient, build)
          ) match {
            case None => wsClient.close()
            case Some(p) => p.termination.foreach(_ => wsClient.close())
          }
        }
    }
  }
}
