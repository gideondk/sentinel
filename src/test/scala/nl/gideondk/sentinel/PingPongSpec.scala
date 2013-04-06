package nl.gideondk.sentinel

import ValidatedFutureIO._
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

/* Ping/Pong test for raw performance, Uses no-ack based flow control (since sequence isn't important and chunk sizes are minimal) */

case class PingPongMessageFormat(s: String)

class PingPongMessageStage extends SymmetricPipelineStage[HasByteOrder, PingPongMessageFormat, ByteString] {
  override def apply(ctx: HasByteOrder) = new SymmetricPipePair[PingPongMessageFormat, ByteString] {
    implicit val byteOrder = ctx.byteOrder

    override val commandPipeline = { msg: PingPongMessageFormat ⇒
      Seq(Right(ByteString(msg.s)))
    }

    override val eventPipeline = { bs: ByteString ⇒
      Seq(Left(PingPongMessageFormat(new String(bs.toArray))))
    }
  }
}

object PingPongServerHandler {
  def handle(event: PingPongMessageFormat): Future[PingPongMessageFormat] = {
    event.s match {
      case "PING" ⇒ Future(PingPongMessageFormat("PONG"))
      case _      ⇒ Future.failed(new Exception("Unknown command"))
    }
  }
}

object PingPongTestHelper {

  def ctx = new HasByteOrder {
    def byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  }

  val stages = new PingPongMessageStage >> new LengthFieldFrame(1000)

  lazy val (pingServer: ActorRef, pingClient: ActorRef) = {
    implicit val actorSystem = akka.actor.ActorSystem("test-system")
    val pingServer = SentinelServer.randomRouting(9999, 16, PingPongServerHandler.handle, "Ping Server")(ctx, stages, false)
    Thread.sleep(1000)

    val pingClient = SentinelClient.randomRouting("localhost", 9999, 4, "Ping Client")(ctx, stages, false)
    (pingServer, pingClient)
  }
}

class PingPongSpec extends Specification {
  //ServerClientTestHelper.init

  "A client" should {
    "be able to ping to the server" in {
      val v = (PingPongTestHelper.pingClient <~< PingPongMessageFormat("PING")).unsafeFulFill.toOption.get
      v == PingPongMessageFormat("PONG")
    }

    "be able to ping to the server in timely fashion" in {
      val num = 200000
      val mulActs = for (i ← 1 to num) yield (PingPongTestHelper.pingClient <~< PingPongMessageFormat("PING"))
      val ioActs = mulActs.toList.map(_.run).sequence
      val futs = ioActs.map(x ⇒ Future.sequence(x.map(_.run)))

      val fut = futs.unsafePerformIO
      BenchmarkHelpers.timed("Ping-Ponging " + num + " requests", num) {
        Await.result(fut, Duration.apply(10, scala.concurrent.duration.SECONDS))
        true
      }
      val res = Await.result(fut, Duration.apply(10, scala.concurrent.duration.SECONDS))
      res.map(_.toOption.get).filterNot(_ == PingPongMessageFormat("PONG")).length == 0
    }
  }
}
