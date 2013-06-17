package nl.gideondk.sentinel

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import org.specs2.mutable.Specification

import akka.actor.ActorRef
import akka.io.LengthFieldFrame
import akka.routing.RandomRouter
import akka.util.{ ByteString, ByteStringBuilder }
import client.{ SentinelClient, commandable }
import scalaz.Scalaz._
import server.SentinelServer

import akka.actor.ActorSystem

object LargerPayloadServerHandler {
  def handle(event: ByteString): Future[ByteString] = {
    val bs = new ByteStringBuilder
    bs.putInt(event.length)(java.nio.ByteOrder.BIG_ENDIAN)

    Future(bs.result)
  }
}

trait LargerPayloadWorkers {
  val serverSystem = ActorSystem("server-system")
  val server = SentinelServer.async(8002, LargerPayloadServerHandler.handle, "File Server")(stages)(serverSystem)

  val clientSystem = ActorSystem("client-system")
  val client = SentinelClient("localhost", 8002, RandomRouter(32), "File Client")(stages)(clientSystem)

  def stages = new LengthFieldFrame(1024 * 1024 * 1024) // 1Gb

  def sendActionsForBS(bs: ByteString, num: Int) = {
    val mulActs = for (i ← 1 to num) yield (client <~< bs)
    val ioActs = mulActs.toList.map(_.get).sequence
    ioActs.map(x ⇒ Future.sequence(x))
  }
}

class LargerPayloadSpec extends Specification with LargerPayloadWorkers {
  sequential
  import LargerPayloadTestHelper._

  "A 0.1mb payload" should {
    "be able to be send correctly" in {
      val bs = randomBSForSize((1024 * 1024 * 0.1).toInt)
      val num = 500
      val fut = sendActionsForBS(bs, num).unsafePerformIO

      BenchmarkHelpers.throughput("Sending " + num + " requests of " + bs.length / 1024.0 / 1024.0 + " mb", bs.length / 1024.0 / 1024.0, num) {
        Await.result(fut, Duration.apply(20, scala.concurrent.duration.SECONDS))
      }
      true
    }
  }

  "A 0.5mb payload" should {
    "be able to be send correctly" in {
      val bs = randomBSForSize((1024 * 1024 * 0.5).toInt)
      val num = 50
      val fut = sendActionsForBS(bs, num).unsafePerformIO

      BenchmarkHelpers.throughput("Sending " + num + " requests of " + bs.length / 1024.0 / 1024.0 + " mb", bs.length / 1024.0 / 1024.0, num) {
        Await.result(fut, Duration.apply(20, scala.concurrent.duration.SECONDS))
        true
      }
      true
    }
  }

  "A 2mb payload" should {
    "be able to be send correctly" in {
      val bs = randomBSForSize((1024 * 1024 * 2).toInt)
      val num = 20
      val fut = sendActionsForBS(bs, num).unsafePerformIO

      BenchmarkHelpers.throughput("Sending " + num + " requests of " + bs.length / 1024.0 / 1024.0 + " mb", bs.length / 1024.0 / 1024.0, num) {
        Await.result(fut, Duration.apply(20, scala.concurrent.duration.SECONDS))
      }
      true
    }
  }

  "A 10mb payload" should {
    "be able to be send correctly" in {
      val bs = randomBSForSize((1024 * 1024 * 10).toInt)
      val num = 5
      val fut = sendActionsForBS(bs, num).unsafePerformIO

      BenchmarkHelpers.throughput("Sending " + num + " requests of " + bs.length / 1024.0 / 1024.0 + " mb", bs.length / 1024.0 / 1024.0, num) {
        Await.result(fut, Duration.apply(20, scala.concurrent.duration.SECONDS))
      }
      true
    }
  }

  "A 30mb payload" should {
    "be able to be send correctly" in {
      val bs = randomBSForSize((1024 * 1024 * 30).toInt)
      val num = 2
      val fut = sendActionsForBS(bs, num).unsafePerformIO

      BenchmarkHelpers.throughput("Sending " + num + " requests of " + bs.length / 1024.0 / 1024.0 + " mb", bs.length / 1024.0 / 1024.0, num) {
        Await.result(fut, Duration.apply(20, scala.concurrent.duration.SECONDS))
      }
      true
    }
  }

  step {
    clientSystem.shutdown()
    serverSystem.shutdown()
  }
}
