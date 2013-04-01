package nl.gideondk.sentinel.server

import nl.gideondk.sentinel._


import scala.collection.mutable.Queue

import akka.actor.IO.IterateeRef
import akka.io._
import akka.io.Tcp._

import akka.actor._
import akka.routing._
import akka.util.ByteString

import akka.event.Logging

import scalaz._
import Scalaz._

import java.net.InetSocketAddress

trait SentinelServerWorker extends Actor {
  val log = Logging(context.system, this)

  /* Whether server should use Ack-based flow control */
  def writeAck: Boolean

  /* Internal Iteratee state */
  val state = IterateeRef.Map.async[ActorRef]()(context.dispatcher)

  def workerDescription: String

  /* Write queue used for ACK based sending */
  val writeState = scala.collection.mutable.Map[ActorRef, Boolean]()
  val messQueue = scala.collection.mutable.Map[ActorRef, Queue[ByteString]]()

  def processRequest: akka.actor.IO.Iteratee[ByteString]

  def genericMessageHandler: Receive = {
    case Connected(remoteAddr, localAddr) ⇒
      log.debug(workerDescription + " connected to client: " + remoteAddr)
      sender ! Register(self)
      val tcpListener = sender
      state(tcpListener) flatMap {
        _ ⇒ akka.actor.IO.repeat(processRequest.map(x => if (writeAck) write(x, tcpListener) else tcpListener ! Write(x)))
      }
      
    case ErrorClosed(cause) ⇒
      log.error("Client disconnected from " + workerDescription + " with cause: " + cause)

    case m: ConnectionClosed ⇒
      log.debug("Client disconnected from " + workerDescription) // TODO: handle the specific cases

    case Received(bytes: ByteString) ⇒
      state(sender)(akka.actor.IO.Chunk(bytes))


    case CommandFailed(cmd: Command) ⇒
      /* If a Nack-based flow control is used, try to resend write message if failed */
      cmd match {
        case w: Write if(!writeAck) => sender ! w 
        case _ => log.debug(workerDescription + " failed command: " + cmd.failureMessage)
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

  def messageHandler: Receive = Map.empty

  def receive = messageHandler orElse genericMessageHandler

  def write(bs: ByteString, tcpWorker: ActorRef) = {
    val writeAvailable = writeState.get(sender).getOrElse(true)
    if (writeAvailable) {
      tcpWorker ! Write(bs, WriteAck)
    }
    else {
      if (messQueue.get(sender).isEmpty) messQueue(sender) = Queue[ByteString]()
      val writeQueue = messQueue(sender)
      writeQueue enqueue bs
    }
  }
}
