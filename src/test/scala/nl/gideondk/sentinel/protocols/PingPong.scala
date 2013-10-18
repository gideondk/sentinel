package nl.gideondk.sentinel.protocols

import akka.io._
import akka.util.ByteString
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import nl.gideondk.sentinel.Action

case class PingPongMessageFormat(s: String)

class PingPongMessageStage extends SymmetricPipelineStage[PipelineContext, PingPongMessageFormat, ByteString] {
  override def apply(ctx: PipelineContext) = new SymmetricPipePair[PingPongMessageFormat, ByteString] {
    implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

    override val commandPipeline = {
      msg: PingPongMessageFormat ⇒
        Seq(Right(ByteString(msg.s)))
    }

    override val eventPipeline = {
      bs: ByteString ⇒
        Seq(Left(PingPongMessageFormat(new String(bs.toArray))))
    }
  }
}

object PingPong {
  val stages = new PingPongMessageStage >> new LengthFieldFrame(1000)
}

object PingPongServerHandler extends Action.Decider[PingPongMessageFormat, PingPongMessageFormat] {
  def process = {
    case PingPongMessageFormat("PING") ⇒
      answer {
        Future(PingPongMessageFormat("PONG"))
      }

    //      case x: String ⇒ throw new Exception("Unknown command: " + x)
    //      case _         ⇒ throw new Exception("Unknown command")
  }
}
//  }