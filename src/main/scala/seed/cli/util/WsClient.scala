package seed.cli.util

import java.net.URI
import java.nio.ByteBuffer

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import seed.Log

class WsClient(serverUri: URI, payload: () => String, log: Log)
    extends WebSocketClient(serverUri) {
  override def onOpen(handshake: ServerHandshake): Unit = {
    log.debug("Connection established")
    send(payload())
  }
  override def onClose(code: Int, reason: String, remote: Boolean): Unit =
    log.debug("Connection closed")
  override def onMessage(message: String): Unit     = print(message)
  override def onMessage(message: ByteBuffer): Unit = {}
  override def onError(ex: Exception): Unit =
    log.error(s"An error occurred: $ex")
}
