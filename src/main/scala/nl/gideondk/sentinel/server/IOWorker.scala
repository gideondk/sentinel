package nl.gideondk.sentinel.server

import java.net.InetSocketAddress

import scala.collection.mutable.Queue

import akka.io._
import akka.io.Tcp._

import akka.actor._
import akka.event.Logging
import akka.util.ByteString

import scalaz.Scalaz._
import nl.gideondk.sentinel._

class SentinelServerIOWorker(val description: String, val ackCount: Int = 10) extends SentinelIOWorker {
  import SentinelServerWorker._

  def baseHandler = {
    case Connected(remoteAddr, localAddr) ⇒
      log.debug(description + " connected to client: " + remoteAddr)
      tcpWorker = sender.point[Option]
      sender ! Register(self)

    case ErrorClosed(cause) ⇒
      log.error("Client disconnected from " + description + " with cause: " + cause)

    case m: ConnectionClosed ⇒
      log.debug("Client disconnected from " + description) // TODO: handle the specific cases

    case Received(bytes: ByteString) ⇒
      context.parent ! RawServerEvent(bytes)

    case rs: RawServerCommand ⇒
      // If the message failed, message sequence isn't certain; tear down line to let client recover.
      if (rs.bs.isFailure)
        tcpWorker.foreach(_ ! ErrorClosed(rs.bs.failed.get.getMessage))
      else
        self ! WriteToTCPWorker(rs.bs.get)
  }
}