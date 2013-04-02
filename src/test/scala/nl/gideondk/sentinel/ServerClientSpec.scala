package nl.gideondk.sentinel

import ValidatedFutureIO._
import server._
import client._

import org.specs2.mutable.Specification

import akka.actor.IO.Chunk
import akka.actor.IO._
import akka.actor._

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

class PingServerWorker extends SentinelServerWorker {
  val writeAck = false
  val workerDescription = "Ping Server Worker"
  val processRequest = for {
    bs ← akka.actor.IO.take(4) // "PING"
  } yield ByteString("PONG")
}

class PingClientWorker extends SentinelClientWorker {
  val writeAck = false
  val workerDescription = "Ping Client Worker"
  val processRequest = for {
    bs ← akka.actor.IO.take(4) // "PONG"
  } yield new String(bs.toArray)
}

object ServerClientTestHelper {
  implicit val actorSystem = ActorSystem("test-system")

  lazy val (pingServer, pingClient) = {
    val pingServer = SentinelServer.randomRouting[PingServerWorker](9999, 4, "Ping Server")
    Thread.sleep(2)
    val pingClient = SentinelClient.randomRouting[PingClientWorker]("localhost", 9999, 4, "Ping Client")
    (pingServer, pingClient)
  }
}

class ServerClientSpec extends Specification {
  //ServerClientTestHelper.init

  "A client" should {
    "be able to ping to the server" in {
      (ServerClientTestHelper.pingClient ?? ByteString("PING")).as[String].unsafeFulFill.toOption.get == "PONG"
    }

    "be able to ping to the server in timely fashion" in {
      val num = 200000
      val mulActs = for (i ← 1 to num) yield (ServerClientTestHelper.pingClient ?? ByteString("PING"))
      val ioActs = mulActs.toList.map(_.run).sequence
      val futs = ioActs.map(x ⇒ Future.sequence(x.map(_.run)))

      val fut = futs.unsafePerformIO
      BenchmarkHelpers.timed("Ping-Ponging "+num+" requests", num) {
        Await.result(fut, Duration.Inf)
        true
      }
    }
  }
}
