package nl.gideondk.sentinel.client

import nl.gideondk.sentinel._

import scala.collection.mutable.Queue

import scalaz._
import Scalaz._

import akka.actor._
import akka.actor.IO.IterateeRef
import akka.util.ByteString
import akka.event.Logging

import akka.io._
import akka.io.Tcp._

import java.net.InetSocketAddress

trait SentinelClientWorker extends Actor with Stash {
  import context.dispatcher

  /* Whether client should use Ack-based flow control */
  def writeAck: Boolean

  /* Internal Iteratee state */
  val state = IterateeRef.async

  val tcp = akka.io.IO(Tcp)(context.system)
  var tcpWorker: Option[ActorRef] = None
  val log = Logging(context.system, this)

  /* Server address to connect to (will normally be initialized by the supervisor) */
  var address: Option[InetSocketAddress] = None

  /* Worker description (for logging purposes) */
  def workerDescription: String

  /* Write queue used for ACK based sending */
  val writeQueue = Queue[ByteString]()
  var writeAvailable = true

  /* Iteratee used for response processing */
  def processRequest: akka.actor.IO.Iteratee[Any]

  override def preStart = {
  }

  override def postStop = {
    tcp ! Close
    state(akka.actor.IO.EOF)
  }

  def genericMessageHandler: Receive = {
    case h: ConnectToHost ⇒
      address = Some(h.address)
      tcp ! Connect(h.address)

    case Connected(remoteAddr, localAddr) ⇒
      sender ! Register(self)
      tcpWorker = sender.point[Option]
      log.debug(workerDescription+" connected to "+remoteAddr)

      /* Unstash all requests, send prior to the server connection */
      unstashAll()

    case ErrorClosed(cause) ⇒
      log.debug(workerDescription+" disconnected from ("+address+") with cause: "+cause)
      throw new DiconnectExceptionWithCause(cause)

    case PeerClosed ⇒
      log.debug(workerDescription+" disconnected from ("+address+")")
      throw new PeerDisconnectedException

    case ConfirmedClosed ⇒
      log.debug(workerDescription+" disconnected from ("+address+")")

    case m: ConnectionClosed ⇒
      log.debug(workerDescription+" disconnected from ("+address+")") // TODO: handle the specific cases
      throw new DisconnectException

    case Received(bytes: ByteString) ⇒
      state(akka.actor.IO.Chunk(bytes))

    case CommandFailed(cmd: Command) ⇒
      /* If a Nack-based flow control is used, try to resend write message if failed */
      cmd match {
        case w: Write if (!writeAck) ⇒ sender ! w
        case _                       ⇒ log.debug(workerDescription+" failed command: "+cmd.failureMessage)
      }

    /* Generic handler for commands, should be extended through the specificMessageHandler for for extended functionality */
    case c: SentinelCommand ⇒
      tcpWorker match {
        case None ⇒ stash()
        case Some(w) ⇒
          for {
            _ ← state
            result ← processRequest
          } yield {
            c.promise.success(result)
            ()
          }

          if (writeAck) write(c.command) else w ! Write(c.command)
      }

    /* WriteAck messages will dequeue and write command in a Ack-based flow control */
    case WriteAck ⇒
      if (writeAck) {
        writeAvailable = true
        if (writeQueue.length > 0)
          write(writeQueue.dequeue())
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
    }
    else {
      writeQueue enqueue bs
    }
  }

  /* Message handler implemented by actor */
  def messageHandler: Receive = Map.empty

  def receive = messageHandler orElse genericMessageHandler
}

case class ConnectToHost(address: InetSocketAddress)

trait WorkerConnectionException extends Exception

case class PeerDisconnectedException extends WorkerConnectionException

case class DisconnectException extends WorkerConnectionException

case class DiconnectExceptionWithCause(c: String) extends WorkerConnectionException
