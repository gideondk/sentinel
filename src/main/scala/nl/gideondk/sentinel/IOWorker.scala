package nl.gideondk.sentinel

import java.net.InetSocketAddress

import scala.collection.mutable.Queue

import akka.io._
import akka.io.Tcp._

import akka.actor._
import akka.event.Logging
import akka.util.ByteString

import scalaz.Scalaz._
import nl.gideondk.sentinel._

trait SentinelIOWorker extends Actor with Stash {
  def ackCount: Int
  def description: String

  val log = Logging(context.system, this)

  var tcpWorker: Option[ActorRef] = None

  var storageOffset = 0
  var storage = Vector.empty[ByteString]
  var stored = 0L

  val maxStored = 100000000L
  val highWatermark = maxStored * 5 / 10
  val lowWatermark = maxStored * 3 / 10
  var suspended = false

  private def currentOffset = storageOffset + storage.size

  def baseHandler: Receive

  def receive = baseHandler orElse writing

  def writing: Receive = {
    {
      case x: WriteToTCPWorker ⇒
        tcpWorker match {
          case None ⇒ stash()
          case Some(w) ⇒
            tcpWorker.foreach(_ ! Write(x.bs, currentOffset))
            buffer(x.bs)
        }

      case ack: Int ⇒
        acknowledge(ack)

      case CommandFailed(Write(_, ack: Int)) ⇒
        tcpWorker.foreach(_ ! ResumeWriting)
        context become (baseHandler orElse buffering(ack))

      case PeerClosed ⇒
        if (storage.isEmpty) context stop self
        else context become closing
    }
  }

  def buffering(nack: Int): Receive = {
    var toAck = ackCount
    var peerClosed = false

    {
      case x: WriteToTCPWorker ⇒
        tcpWorker match {
          case None ⇒ stash()
          case Some(w) ⇒
            buffer(x.bs)
        }

      case WritingResumed         ⇒ writeFirst()
      case PeerClosed             ⇒ peerClosed = true
      case ack: Int if ack < nack ⇒ acknowledge(ack)
      case ack: Int ⇒
        acknowledge(ack)
        if (storage.nonEmpty) {
          if (toAck > 0) {
            // stay in ACK-based mode for a while
            writeFirst()
            toAck -= 1
          } else {
            // then return to NACK-based again
            writeAll()
            context become (if (peerClosed) closing else writing)
          }
        } else if (peerClosed) context stop self
        else context become (baseHandler orElse writing)
    }
  }

  def closing: Receive = {
    case CommandFailed(_: Write) ⇒
      tcpWorker.foreach(_ ! ResumeWriting)
      context.become({

        case WritingResumed ⇒
          writeAll()
          context.unbecome()

        case ack: Int ⇒ acknowledge(ack)

      }, discardOld = false)

    case ack: Int ⇒
      acknowledge(ack)
      if (storage.isEmpty) context stop self
  }

  private def buffer(data: ByteString): Unit = {
    storage :+= data
    stored += data.size

    if (stored > maxStored) {
      context stop self

    } else if (stored > highWatermark) {
      tcpWorker.foreach(_ ! SuspendReading)
      suspended = true
    }
  }

  private def acknowledge(ack: Int): Unit = {
    require(ack == storageOffset, s"received ack $ack at $storageOffset")
    require(storage.nonEmpty, s"storage was empty at ack $ack")

    val size = storage(0).size
    stored -= size

    storageOffset += 1
    storage = storage drop 1

    if (suspended && stored < lowWatermark) {
      tcpWorker.foreach(_ ! ResumeReading)
      suspended = false
    }
  }

  private def writeFirst(): Unit = {
    tcpWorker.foreach(_ ! Write(storage(0), storageOffset))
  }

  private def writeAll(): Unit = {
    for ((data, i) ← storage.zipWithIndex) {
      tcpWorker.foreach(_ ! Write(data, storageOffset + i))
    }
  }
}

case class WriteToTCPWorker(bs: ByteString)