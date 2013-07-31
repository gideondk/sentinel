package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Random

import org.specs2.mutable.Specification

import akka.actor.ActorRef
import akka.io.{ LengthFieldFrame, PipelineContext, SymmetricPipePair, SymmetricPipelineStage }
import akka.routing.RandomRouter
import akka.util.{ ByteString, ByteStringBuilder }

import client._
import server._

import akka.actor.ActorSystem

/* Sequence test for which checks if multi-threaded responses are handled and send back in correct sequence */

case class SequenceMessageFormat(l: Long)

class SequenceMessageStage extends SymmetricPipelineStage[PipelineContext, SequenceMessageFormat, ByteString] {
  override def apply(ctx: PipelineContext) = new SymmetricPipePair[SequenceMessageFormat, ByteString] {
    implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

    override val commandPipeline = { msg: SequenceMessageFormat ⇒
      val bsb = new ByteStringBuilder()
      bsb.putLong(msg.l)
      bsb ++= LargerPayloadTestHelper.randomBSForSize((1024 * 1024 * 1).toInt) // 1MB
      Seq(Right(bsb.result))
    }

    override val eventPipeline = { bs: ByteString ⇒
      val bi = bs.iterator
      val long = bi.getLong
      val rest = bi.toByteString
      Seq(Left(SequenceMessageFormat(long)))
    }
  }
}

object SequenceServerHandler {
  def handle(event: SequenceMessageFormat): Future[SequenceMessageFormat] = {
    val sleep = 1
    Future {
      Thread.sleep(Random.nextInt(10))
      event
    }
  }
}

trait SequenceWorkers {
  val stages = new SequenceMessageStage >> new LengthFieldFrame(1024 * 1024 * 10)

  implicit val actorSystem = ActorSystem("test-system")

  val server = SentinelServer.async(8001, SequenceServerHandler.handle, "Ping Server")(stages)
  val client = SentinelClient.waiting("localhost", 8001, RandomRouter(4), "Ping Client")(stages)
}

class SequenceSpec extends Specification with SequenceWorkers {
  "A client" should {
    "retrieve items in correct sequence" in {
      val num = 100
      val sequence = for (i ← 0 to num) yield SequenceMessageFormat(i)
      val mulActs = Task.sequence(sequence.map(x ⇒ client <~< x).toList)

      val res = mulActs.run(Duration.apply(10, scala.concurrent.duration.SECONDS))
      res.get.corresponds(sequence) { _ == _ }
    }
  }

  step {
    actorSystem.shutdown()
  }
}
