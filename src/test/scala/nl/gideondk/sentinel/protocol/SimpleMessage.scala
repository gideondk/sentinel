package nl.gideondk.sentinel.protocol

import akka.stream.scaladsl.{ BidiFlow, Framing }
import akka.util.{ ByteString, ByteStringBuilder }
import nl.gideondk.sentinel._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

sealed trait SimpleMessageFormat {
  def payload: String
}

case class SimpleCommand(cmd: Int, payload: String) extends SimpleMessageFormat

// 1
case class SimpleReply(payload: String) extends SimpleMessageFormat

// 2
case class SimpleStreamChunk(payload: String) extends SimpleMessageFormat

// 3
case class SimpleError(payload: String) extends SimpleMessageFormat

object SimpleMessage {
  val PING_PONG = 1
  val TOTAL_CHUNK_SIZE = 2
  val GENERATE_NUMBERS = 3
  val CHUNK_LENGTH = 4
  val ECHO = 5

  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

  def deserialize(bs: ByteString): SimpleMessageFormat = {
    val iter = bs.iterator
    iter.getByte.toInt match {
      case 1 ⇒
        SimpleCommand(iter.getInt, new String(iter.toByteString.toArray))
      case 2 ⇒
        SimpleReply(new String(iter.toByteString.toArray))
      case 3 ⇒
        SimpleStreamChunk(new String(iter.toByteString.toArray))
      case 4 ⇒
        SimpleError(new String(iter.toByteString.toArray))
    }
  }

  def serialize(m: SimpleMessageFormat): ByteString = {
    val bsb = new ByteStringBuilder()
    m match {
      case x: SimpleCommand ⇒
        bsb.putByte(1.toByte)
        bsb.putInt(x.cmd)
        bsb.putBytes(x.payload.getBytes)
      case x: SimpleReply ⇒
        bsb.putByte(2.toByte)
        bsb.putBytes(x.payload.getBytes)
      case x: SimpleStreamChunk ⇒
        bsb.putByte(3.toByte)
        bsb.putBytes(x.payload.getBytes)
      case x: SimpleError ⇒
        bsb.putByte(4.toByte)
        bsb.putBytes(x.payload.getBytes)
      case _ ⇒
    }
    bsb.result
  }

  val flow = BidiFlow.fromFunctions(serialize, deserialize)

  def protocol = flow.atop(Framing.simpleFramingProtocol(1024))
}

import SimpleMessage._

object SimpleHandler extends Resolver[SimpleMessageFormat] {
  def process: PartialFunction[SimpleMessageFormat, Action] = {
    case SimpleStreamChunk(x)              ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case x: SimpleError                    ⇒ ConsumerAction.AcceptError
    case x: SimpleReply                    ⇒ ConsumerAction.AcceptSignal
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
    case x                                 ⇒ println("Unhandled: " + x); ConsumerAction.Ignore
  }
}

object SimpleServerHandler extends Resolver[SimpleMessageFormat] {
  def process: PartialFunction[SimpleMessageFormat, Action] = {
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
    case x                                 ⇒ println("Unhandled: " + x); ConsumerAction.Ignore

    //    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ ProducerAction.ConsumeStream { x: SimpleCommand ⇒
    //      s: Enumerator[SimpleStreamChunk] ⇒
    //        s |>>> Iteratee.fold(0) { (b, a) ⇒ b + a.payload.length } map (x ⇒ SimpleReply(x.toString))
    //    }
    //    case SimpleCommand(GENERATE_NUMBERS, payload) ⇒ ProducerAction.ProduceStream { x: SimpleCommand ⇒
    //      val count = payload.toInt
    //      Future((Enumerator(List.range(0, count): _*) &> Enumeratee.map(x ⇒ SimpleStreamChunk(x.toString))) >>> Enumerator(SimpleStreamChunk("")))
    //    }
    //    case SimpleCommand(ECHO, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply(x.payload)) }
  }
}