package seed.cli

import java.net.URI

import seed.Log
import seed.cli.util.WsClient
import seed.Cli.Command

object BuildEvents {
  def ui(command: Command.BuildEvents, log: Log): Unit = {
    val connection = command.webSocket
    val uri = s"ws://${connection.host}:${connection.port}"
    log.debug(s"Sending command to $uri...")
    val client = new WsClient(new URI(uri), () => {
      import io.circe.syntax._
      (WsCommand.BuildEvents: WsCommand).asJson.noSpaces
    }, log)
    client.connect()
  }
}
