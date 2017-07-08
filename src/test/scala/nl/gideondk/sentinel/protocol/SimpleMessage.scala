package nl.gideondk.sentinel.protocol

import akka.stream.Materializer
import akka.stream.scaladsl.{ BidiFlow, Framing, Sink, Source }
import akka.util.{ ByteString, ByteStringBuilder }
import nl.gideondk.sentinel.pipeline.Resolver

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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

import nl.gideondk.sentinel.protocol.SimpleMessage._

object SimpleHandler extends Resolver[SimpleMessageFormat] {
  def process(implicit mat: Materializer): PartialFunction[SimpleMessageFormat, Action] = {
    case SimpleStreamChunk(x)              ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case x: SimpleError                    ⇒ ConsumerAction.AcceptError
    case x: SimpleReply                    ⇒ ConsumerAction.AcceptSignal
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
    case x                                 ⇒ println("Unhandled: " + x); ConsumerAction.Ignore
  }
}

object SimpleServerHandler extends Resolver[SimpleMessageFormat] {
  def process(implicit mat: Materializer): PartialFunction[SimpleMessageFormat, Action] = {
    case SimpleStreamChunk(x)              ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ ProducerAction.ConsumeStream { x: Source[SimpleStreamChunk, Any] ⇒
      x.runWith(Sink.fold[Int, SimpleMessageFormat](0) { (b, a) ⇒ b + a.payload.length }).map(x ⇒ SimpleReply(x.toString))
    }
    case SimpleCommand(GENERATE_NUMBERS, payload) ⇒ ProducerAction.ProduceStream { x: SimpleCommand ⇒
      val count = payload.toInt
      Future(Source(List.range(0, count)).map(x ⇒ SimpleStreamChunk(x.toString)) ++ Source.single(SimpleStreamChunk("")))
    }
    case SimpleCommand(ECHO, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply(x.payload)) }
    case x                            ⇒ println("Unhandled: " + x); ConsumerAction.Ignore
  }
}