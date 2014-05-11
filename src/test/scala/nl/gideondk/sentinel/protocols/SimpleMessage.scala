package nl.gideondk.sentinel.protocols

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.io._
import akka.util.{ ByteString, ByteStringBuilder }

import nl.gideondk.sentinel._
import play.api.libs.iteratee._

trait SimpleMessageFormat {
  def payload: String
}

case class SimpleCommand(cmd: Int, payload: String) extends SimpleMessageFormat // 1
case class SimpleReply(payload: String) extends SimpleMessageFormat // 2
case class SimpleStreamChunk(payload: String) extends SimpleMessageFormat // 3
case class SimpleError(payload: String) extends SimpleMessageFormat // 4

class PingPongMessageStage extends SymmetricPipelineStage[PipelineContext, SimpleMessageFormat, ByteString] {
  override def apply(ctx: PipelineContext) = new SymmetricPipePair[SimpleMessageFormat, ByteString] {
    implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

    override val commandPipeline = {
      msg: SimpleMessageFormat ⇒
        {
          val bsb = new ByteStringBuilder()
          msg match {
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
          Seq(Right(bsb.result))
        }

    }

    override val eventPipeline = {
      bs: ByteString ⇒
        val iter = bs.iterator
        iter.getByte.toInt match {
          case 1 ⇒
            Seq(Left(SimpleCommand(iter.getInt, new String(iter.toByteString.toArray))))
          case 2 ⇒
            Seq(Left(SimpleReply(new String(iter.toByteString.toArray))))
          case 3 ⇒
            Seq(Left(SimpleStreamChunk(new String(iter.toByteString.toArray))))
          case 4 ⇒
            Seq(Left(SimpleError(new String(iter.toByteString.toArray))))
        }

    }
  }
}

object SimpleMessage {
  val stages = new PingPongMessageStage >> new LengthFieldFrame(1024 * 1024)

  val PING_PONG = 1
  val TOTAL_CHUNK_SIZE = 2
  val GENERATE_NUMBERS = 3
  val CHUNK_LENGTH = 4
  val ECHO = 5
}

import SimpleMessage._
trait DefaultSimpleMessageHandler extends Resolver[SimpleMessageFormat, SimpleMessageFormat] {
  def process = {
    case SimpleStreamChunk(x) ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case x: SimpleError       ⇒ ConsumerAction.AcceptError
    case x: SimpleReply       ⇒ ConsumerAction.AcceptSignal
  }
}

object SimpleClientHandler extends DefaultSimpleMessageHandler

object SimpleServerHandler extends DefaultSimpleMessageHandler {

  override def process = super.process orElse {
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ ProducerAction.ConsumeStream { x: SimpleCommand ⇒
      s: Enumerator[SimpleStreamChunk] ⇒
        s |>>> Iteratee.fold(0) { (b, a) ⇒ b + a.payload.length } map (x ⇒ SimpleReply(x.toString))
    }
    case SimpleCommand(GENERATE_NUMBERS, payload) ⇒ ProducerAction.ProduceStream { x: SimpleCommand ⇒
      val count = payload.toInt
      Future((Enumerator(List.range(0, count): _*) &> Enumeratee.map(x ⇒ SimpleStreamChunk(x.toString))) >>> Enumerator(SimpleStreamChunk("")))
    }
    case SimpleCommand(ECHO, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply(x.payload)) }
  }
}