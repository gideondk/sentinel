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

class SentinelClientIOWorker(description: String, writeAck: Boolean) extends Actor with Stash {
  import SentinelClientWorker._

  val log = Logging(context.system, this)
  val tcp = akka.io.IO(Tcp)(context.system)

  var tcpWorker: Option[ActorRef] = None
  var address: Option[InetSocketAddress] = None

  /* Write queue used for ACK based sending */
  val writeQueue = Queue[ByteString]()
  var writeAvailable = true

  override def postStop = {
    tcp ! Close
  }

  def receive = {
    case h: ConnectToHost ⇒
      address = Some(h.address)
      tcp ! Connect(h.address)

    case Connected(remoteAddr, localAddr) ⇒
      sender ! Register(self)
      tcpWorker = sender.point[Option]
      log.debug(description + " connected to " + remoteAddr)

      /* Unstash all requests, send prior to the server connection */
      unstashAll()

    case ErrorClosed(cause) ⇒
      log.debug(description + " disconnected from (" + address + ") with cause: " + cause)
      throw new DiconnectExceptionWithCause(cause)

    case PeerClosed ⇒
      log.debug(description + " disconnected from (" + address + ")")
      throw new PeerDisconnectedException

    case ConfirmedClosed ⇒
      log.debug(description + " disconnected from (" + address + ")")

    case m: ConnectionClosed ⇒
      log.debug(description + " disconnected from (" + address + ")") // TODO: handle the specific cases
      throw new DisconnectException

    case Received(bytes: ByteString) ⇒
      context.parent ! RawEvent(bytes)

    case CommandFailed(cmd: Command) ⇒
      /* If a Nack-based flow control is used, try to resend write message if failed */
      cmd match {
        case w: Write if (!writeAck) ⇒ sender ! w
        case _                       ⇒ log.debug(description + " failed command: " + cmd.failureMessage)
      }

    /* Generic handler for commands, should be extended through the specificMessageHandler for for extended functionality */
    case c: RawCommand ⇒
      tcpWorker match {
        case None    ⇒ stash()
        case Some(w) ⇒ if (writeAck) write(c.bs) else w ! Write(c.bs)
      }

    /* WriteAck messages will dequeue and write command in a Ack-based flow control */
    case WriteAck ⇒
      if (writeAck) {
        writeAvailable = true
        if (writeQueue.length > 0) write(writeQueue.dequeue())
      }
  }

  /* write function, used in a Ack-based environment, sends command directly if current worker is 'writeAvailable', enqueuing command when it's not */
  def write(bs: ByteString) = {
    if (writeAvailable) {
      tcpWorker match {
        case None ⇒ throw new IllegalStateException("Trying to write to a undefined worker")
        case Some(w) ⇒
          writeAvailable = false
          w ! Write(bs, WriteAck)
      }
    } else {
      writeQueue enqueue bs
    }
  }
}