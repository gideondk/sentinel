package nl.gideondk.sentinel.protocols

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz.contrib.std.scalaFuture.futureInstance

import akka.io._
import akka.util.{ ByteString, ByteStringBuilder }

import scalaz.stream._

import nl.gideondk.sentinel._
import CatchableFuture._

trait SimpleMessageFormat {
  def payload: String
}

case class SimpleCommand(cmd: Int, payload: String) extends SimpleMessageFormat // 1
case class SimpleReply(payload: String) extends SimpleMessageFormat // 2
case class SimpleStreamChunk(payload: String) extends SimpleMessageFormat // 3

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
        }

    }
  }
}

object SimpleMessage {
  val stages = new PingPongMessageStage >> new LengthFieldFrame(1000)

  val PING_PONG_COMMAND = 1
  val TOTAL_CHUNK_SIZE = 2
}

object SimpleServerHandler extends SentinelResolver[SimpleMessageFormat, SimpleMessageFormat] {
  import SimpleMessage._
  def process = {
    case SimpleCommand(PING_PONG_COMMAND, payload) ⇒ answer { x ⇒ Future(SimpleReply("PONG")) }
    
    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ consumeStream { x ⇒
      s ⇒
        s pipe process1.fold(0) { (b, a) ⇒ b + a.payload.length } runLastOr (throw new Exception("")) map (x ⇒ SimpleReply(x.toString))
    }

    case SimpleStreamChunk(x) ⇒ if (x.length > 0) consume else endStream

    case _                    ⇒ throw new Exception("Unknown command")
  }
}