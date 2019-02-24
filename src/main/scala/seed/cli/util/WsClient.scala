package seed.cli.util

import java.net.URI
import java.nio.ByteBuffer

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import seed.Log

class WsClient(serverUri: URI, payload: () => String)
  extends WebSocketClient(serverUri)
{
  override def onOpen(handshake: ServerHandshake): Unit = {
    Log.debug("Connection established")
    send(payload())
  }
  override def onClose(code: Int, reason: String, remote: Boolean): Unit =
    Log.debug("Connection closed")
  override def onMessage(message: String): Unit = println(message)
  override def onMessage(message: ByteBuffer): Unit = {}
  override def onError(ex: Exception): Unit =
    Log.error(s"An error occurred: $ex")
}
