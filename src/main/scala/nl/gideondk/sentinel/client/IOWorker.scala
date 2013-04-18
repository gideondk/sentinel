package nl.gideondk.sentinel.client

import java.net.InetSocketAddress

import scala.collection.mutable.Queue

import akka.io._
import akka.io.Tcp._

import akka.actor._
import akka.event.Logging
import akka.util.ByteString

import scalaz.Scalaz._
import nl.gideondk.sentinel._

class SentinelClientIOWorker(val description: String, val ackCount: Int = 10) extends SentinelIOWorker {
  import SentinelClientWorker._

  val tcp = akka.io.IO(Tcp)(context.system)
  var address: Option[InetSocketAddress] = None

  override def postStop = {
    tcp ! Close
  }

  def baseHandler: Receive = {
    case h: ConnectToHost ⇒
      address = Some(h.address)
      tcp ! Connect(h.address)

    case Connected(remoteAddr, localAddr) ⇒
      sender ! Register(self)
      tcpWorker = sender.point[Option]
      log.debug(description + " connected to " + remoteAddr)

      /* Unstash all requests, send prior to the server connection */
      unstashAll()

    case c: RawCommand  ⇒ self ! WriteToTCPWorker(c.bs)

    case Received(data) ⇒ context.parent ! RawEvent(data)
  }
}