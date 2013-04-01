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

import concurrent.{ Promise, Future }

import akka.util.{ ByteStringBuilder, ByteString }
import akka.routing.RandomRouter

import scala.concurrent.ExecutionContext.Implicits.global

import concurrent._
import concurrent.duration._

class PingServerWorker extends SentinelServerWorker {
  val writeAck = false
  val workerDescription = "Ping Server Worker"
  val processRequest = for {
    bs <- akka.actor.IO.take(4) // "PING"
  } yield {
    val builder = new ByteStringBuilder
    builder.putBytes("PONG".getBytes)
    builder.result
  }
}

class PingClientWorker extends SentinelClientWorker {
  val writeAck = false
  val workerDescription = "Ping Client Worker"
  val processRequest = for {
    bs <- akka.actor.IO.take(4) // "PONG"
  } yield new String(bs.toArray)
}

object ServerClientTestHelper {
    implicit val actorSystem = ActorSystem("test-system")

    var pingServer: ActorRef = _
    var pingClient: ActorRef = _

    def init {
      pingServer = SentinelServer.randomRouting[PingServerWorker](9999, 4, "Ping Server")
      pingClient = SentinelClient.randomRouting[PingClientWorker]("localhost", 9999, 4, "Ping Client")
    }
}

class ServerClientSpec extends Specification  {

  def timed(desc: String, n: Int)(benchmark: ⇒ Unit) = {
    println("* " + desc)
    val t = System.currentTimeMillis
    benchmark
    val d = System.currentTimeMillis - t

    println("* - number of ops/s: "+n / (d / 1000.0)+"\n")
  }

  ServerClientTestHelper.init

  "A client" should {
    "be able to ping to the server" in {
      for (i ← 0 to 200) {
        Thread.sleep(5)
      
        val num = 400000
        val mulActs = for (i <- 1 to num) yield (ServerClientTestHelper.pingClient ?? ByteString("PING"))
        //val ioActs = ValidatedFutureIO.sequence(mulActs.toList)
        val ioActs = mulActs.toList.map(_.run).sequence
        val futs = ioActs.map(x => Future.sequence(x.map(_.run)))
       

        val fut = futs.unsafePerformIO
        timed("aaa", num){
          Await.result(fut, Duration.Inf)
        }
        true
      }
    }
  }
}
