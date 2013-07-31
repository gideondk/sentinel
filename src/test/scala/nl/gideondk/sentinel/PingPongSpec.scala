package nl.gideondk.sentinel

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

import org.specs2.mutable.Specification

import akka.actor.ActorRef
import akka.io.{ LengthFieldFrame, PipelineContext, SymmetricPipePair, SymmetricPipelineStage }
import akka.routing.RandomRouter
import akka.util.ByteString
import server._
import client._

import akka.actor.ActorSystem

case class PingPongMessageFormat(s: String)

class PingPongMessageStage extends SymmetricPipelineStage[PipelineContext, PingPongMessageFormat, ByteString] {
  override def apply(ctx: PipelineContext) = new SymmetricPipePair[PingPongMessageFormat, ByteString] {
    implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

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
    Future(event.s match {
      case "PING"    ⇒ PingPongMessageFormat("PONG")
      case x: String ⇒ throw new Exception("Unknown command: " + x)
      case _         ⇒ throw new Exception("Unknown command")
    })
  }
}

trait PingPongWorkers {
  val stages = new PingPongMessageStage >> new LengthFieldFrame(1000)

  val serverSystem = ActorSystem("ping-server-system")
  val pingServer = SentinelServer.async(8000, PingPongServerHandler.handle, "Ping Server")(stages)(serverSystem)

  val clientSystem = ActorSystem("ping-client-system")
  val pingClient = SentinelClient.randomRouting("localhost", 8000, 16, "Ping Client")(stages)(clientSystem)
}

class PingPongSpec extends Specification with PingPongWorkers {
  sequential

  "A client" should {
    "be able to ping to the server" in {
      implicit val duration = Duration(10, scala.concurrent.duration.SECONDS)
      val v = (pingClient <~< PingPongMessageFormat("PING")).run

      v == Try(PingPongMessageFormat("PONG"))
    }

    "server should disconnect clients on unhandled exceptions" in {
      implicit val duration = Duration(10, scala.concurrent.duration.SECONDS)
      val v = (pingClient <~< PingPongMessageFormat("PINGI")).run
      v.isFailure
    }

    "server and client be able to handle multiple concurrent requests" in {
      val num = 20000

      val mulActs = for (i ← 1 to num) yield (pingClient <~< PingPongMessageFormat("PING"))
      val tasks = Task.sequence(mulActs.toList)

      val fut = tasks.start

      val res = Await.result(fut, Duration(10, scala.concurrent.duration.SECONDS))
      res.get.length == num && res.get.filterNot(_ == PingPongMessageFormat("PONG")).length == 0
    }
  }

  step {
    clientSystem.shutdown()
    serverSystem.shutdown()
  }
}
