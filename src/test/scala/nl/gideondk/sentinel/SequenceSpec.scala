package nl.gideondk.sentinel

import Task._
import server._
import client._
import org.specs2.mutable.Specification
import akka.actor.IO.Chunk
import akka.actor.IO._
import akka.actor._
import akka.io._
import java.util.Date
import scalaz._
import Scalaz._
import effect._
import concurrent.Await
import concurrent.duration.Duration
import akka.util.{ ByteStringBuilder, ByteString }
import akka.routing.RandomRouter
import scala.concurrent.ExecutionContext.Implicits.global
import concurrent._
import concurrent.duration._
import scala.annotation.tailrec
import scala.util.{ Try, Success, Failure }
import java.nio.ByteOrder
import scala.util.Random

/* Sequence test for which checks if multi-threaded responses are handled and send back in correct sequence */

case class SequenceMessageFormat(l: Long)

class SequenceMessageStage extends SymmetricPipelineStage[HasByteOrder, SequenceMessageFormat, ByteString] {
  override def apply(ctx: HasByteOrder) = new SymmetricPipePair[SequenceMessageFormat, ByteString] {
    implicit val byteOrder = ctx.byteOrder

    override val commandPipeline = { msg: SequenceMessageFormat ⇒
      val bsb = new ByteStringBuilder()
      bsb.putLong(msg.l)
      Seq(Right(bsb.result))
    }

    override val eventPipeline = { bs: ByteString ⇒
      val bi = bs.iterator
      Seq(Left(SequenceMessageFormat(bi.getLong)))
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

object SequenceTestHelper {

  def ctx = new HasByteOrder {
    def byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  }

  val stages = new SequenceMessageStage >> new LengthFieldFrame(1000)

  lazy val (server: ActorRef, client: ActorRef) = {
    implicit val actorSystem = akka.actor.ActorSystem("test-system")
    val server = SentinelServer(8888, SequenceServerHandler.handle, "Ping Server")(ctx, stages, 10)
    Thread.sleep(1000)

    val client = SentinelClient.randomRouting("localhost", 8888, 4, "Ping Client")(ctx, stages, 10)
    (server, client)
  }
}

class SequenceSpec extends Specification {
  //ServerClientTestHelper.init

  "A client" should {
    "retrieve items in correct sequence" in {
      val num = 5000
      val sequence = for (i ← 0 to num) yield SequenceMessageFormat(i)
      val mulActs = Task.sequence(sequence.map(x ⇒ SequenceTestHelper.client <~< x).toList)

      val res = mulActs.run(Duration.apply(10, scala.concurrent.duration.SECONDS))
      res.get.corresponds(sequence) { _ == _ }
    }
  }
}
