package seed.cli.util

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

import io.circe.parser.decode

import seed.Log
import seed.cli.WsCommand

class WsServer(
  address: InetSocketAddress,
  onDisconnect: WebSocket => Unit,
  evalCommand: (WsServer, WebSocket, WsCommand) => Unit,
  log: Log
) extends WebSocketServer(address) {
  setReuseAddr(true)

  def clientIp(conn: WebSocket): String =
    conn.getRemoteSocketAddress.getAddress.getHostAddress

  override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit =
    log.debug(s"Client ${Ansi.italic(clientIp(conn))} connected")
  override def onClose(
    conn: WebSocket,
    code: Int,
    reason: String,
    remote: Boolean
  ): Unit = {
    log.debug(s"Client ${Ansi.italic(clientIp(conn))} disconnected")
    onDisconnect(conn)
  }
  override def onError(conn: WebSocket, ex: Exception): Unit =
    ex.printStackTrace()
  override def onStart(): Unit = {
    setConnectionLostTimeout(100)
    log.info(s"WebSocket server started on ${Ansi
      .italic(s"${address.getHostName}:${address.getPort}")}")
  }
  override def onMessage(conn: WebSocket, message: String): Unit =
    try {
      decode[WsCommand](message) match {
        case Left(e) =>
          log.error(
            s"Could not process message from ${Ansi.italic(clientIp(conn))}: $e"
          )
          conn.send(e.toString)
        case Right(c) =>
          log.debug(
            s"${Ansi.italic(c.description)} command received from ${Ansi.italic(clientIp(conn))}"
          )
          evalCommand(this, conn, c)
      }
    } catch {
      case t: Throwable => t.printStackTrace()
    }
  override def onMessage(conn: WebSocket, message: ByteBuffer): Unit = {}
}
