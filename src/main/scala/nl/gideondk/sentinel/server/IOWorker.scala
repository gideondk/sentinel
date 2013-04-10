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

class SentinelServerIOWorker(description: String, writeAck: Boolean) extends Actor with Stash {
  import SentinelServerWorker._
  val log = Logging(context.system, this)

  /* Write queue used for ACK based sending */
  val writeState = scala.collection.mutable.Map[ActorRef, Boolean]()
  val messQueue = scala.collection.mutable.Map[ActorRef, Queue[ByteString]]()

  def receive = {
    case Connected(remoteAddr, localAddr) ⇒
      log.debug(description + " connected to client: " + remoteAddr)
      sender ! Register(self)

    case ErrorClosed(cause) ⇒
      log.error("Client disconnected from " + description + " with cause: " + cause)
      cleanupTCPWorker(sender)

    case m: ConnectionClosed ⇒
      log.debug("Client disconnected from " + description) // TODO: handle the specific cases
      cleanupTCPWorker(sender)

    case Received(bytes: ByteString) ⇒
      context.parent ! RawServerEvent(sender, bytes)

    case rs: RawServerCommand ⇒
      // If the message failed, message sequence isn't certain; tear down line to let client recover.
      if (rs.bs.isFailure)
        rs.tcpWorker ! ErrorClosed(rs.bs.failed.get.getMessage)
      else if (writeAck) write(rs.bs.get, rs.tcpWorker) else rs.tcpWorker ! Write(rs.bs.get)

    case CommandFailed(cmd: Command) ⇒
      /* If a Nack-based flow control is used, try to resend write message if failed */
      cmd match {
        case w: Write if (!writeAck) ⇒ sender ! w
        case _                       ⇒ log.debug(description + " failed command: " + cmd.failureMessage)
      }

    case WriteAck ⇒
      if (writeAck) {
        writeState(sender) = true
        if (messQueue.get(sender).isEmpty) messQueue(sender) = Queue[ByteString]()
        val writeQueue = messQueue(sender)
        if (writeQueue.length > 0)
          write(writeQueue.dequeue(), sender)
      }
  }

  def write(bs: ByteString, tcpWorker: ActorRef) = {
    val writeAvailable = writeState.get(sender).getOrElse(true)
    if (writeAvailable) {
      tcpWorker ! Write(bs, WriteAck)
    } else {
      if (messQueue.get(sender).isEmpty) messQueue(sender) = Queue[ByteString]()
      val writeQueue = messQueue(sender)
      writeQueue enqueue bs
    }
  }

  def cleanupTCPWorker(actor: ActorRef) = {
    writeState -= actor
    messQueue -= actor
  }
}