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

/* Base IO Worker, Blatantly ripped of from Roland Kuhn's example code */
trait SentinelIOWorker extends Actor with Stash {
  def description: String
  def ackCount: Int
  def maxBufferSize: Long

  val log = Logging(context.system, this)

  var tcpWorker: Option[ActorRef] = None

  var bufferOffset = 0
  var buffer = Vector.empty[ByteString]
  var bufferSize = 0L

  val highWatermark = maxBufferSize * 5 / 10
  val lowWatermark = maxBufferSize * 3 / 10

  var suspended = false

  private def currentOffset = bufferOffset + buffer.size

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
        if (buffer.isEmpty) context stop self
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
        if (buffer.nonEmpty) {
          if (toAck > 0) {
            // stay in ACK-based mode for a while
            writeFirst()
            toAck -= 1
          } else {
            // then return to NACK-based again
            writeAll()
            context become (if (peerClosed) closing else (baseHandler orElse writing))
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
      if (buffer.isEmpty) context stop self
  }

  private def buffer(data: ByteString): Unit = {
    buffer :+= data
    bufferSize += data.size

    if (bufferSize > maxBufferSize) {
      context stop self
    } else if (bufferSize > highWatermark) {
      tcpWorker.foreach(_ ! SuspendReading)
      suspended = true
    }
  }

  private def acknowledge(ack: Int): Unit = {
    require(ack == bufferOffset, s"received ack $ack at $bufferOffset")
    require(buffer.nonEmpty, s"storage was empty at ack $ack")

    val size = buffer(0).size
    bufferSize -= size

    bufferOffset += 1
    buffer = buffer drop 1

    if (suspended && bufferSize < lowWatermark) {
      tcpWorker.foreach(_ ! ResumeReading)
      suspended = false
    }
  }

  private def writeFirst(): Unit = {
    tcpWorker.foreach(_ ! Write(buffer(0), bufferOffset))
  }

  private def writeAll(): Unit = {
    for ((data, i) ← buffer.zipWithIndex) {
      tcpWorker.foreach(_ ! Write(data, bufferOffset + i))
    }
  }
}

case class WriteToTCPWorker(bs: ByteString)