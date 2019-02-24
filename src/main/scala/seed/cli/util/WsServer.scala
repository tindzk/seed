package seed.cli.util

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

import io.circe.parser.decode

import seed.Log
import seed.cli.WsCommand

class WsServer(address: InetSocketAddress,
               onDisconnect: WebSocket => Unit,
               evalCommand: (WsServer, WebSocket, WsCommand) => Unit
              ) extends WebSocketServer(address) {
  setReuseAddr(true)

  def clientIp(conn: WebSocket): String = conn.getRemoteSocketAddress.getAddress.getHostAddress

  override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit =
    Log.debug("Client " + clientIp(conn) + " connected")
  override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
    Log.debug("Client " + clientIp(conn) + " disconnected")
    onDisconnect(conn)
  }
  override def onError(conn: WebSocket, ex: Exception): Unit = ex.printStackTrace()
  override def onStart(): Unit = setConnectionLostTimeout(100)
  override def onMessage(conn: WebSocket, message: String): Unit = {
    decode[WsCommand](message) match {
      case Left(e) =>
        Log.error("Could not process message from " + clientIp(conn) + ": " + e)
        conn.send(e.toString)
      case Right(c) =>
        Log.debug("Message from " + clientIp(conn) + ": " + c)
        evalCommand(this, conn, c)
    }
  }
  override def onMessage(conn: WebSocket, message: ByteBuffer): Unit = {}
}
