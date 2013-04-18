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

object LargerPayloadServerHandler {
  def handle(event: ByteString): Future[ByteString] = {
    val bs = new ByteStringBuilder
    bs.putInt(event.length)(java.nio.ByteOrder.BIG_ENDIAN)

    Future(bs.result)
  }
}

object LargerPayloadTestHelper {

  def ctx = new HasByteOrder {
    def byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  }

  val stages = new LengthFieldFrame(1024 * 1024 * 1024) // 1Gb

  lazy val (server: ActorRef, client: ActorRef) = {
    implicit val actorSystem = akka.actor.ActorSystem("test-system")
    val server = SentinelServer.randomRouting(7777, 32, LargerPayloadServerHandler.handle, "File Server")(ctx, stages, true)
    Thread.sleep(1000)

    val client = SentinelClient.randomRouting("localhost", 7777, 4, "File Client")(ctx, stages, true)
    (server, client)
  }

  def randomBSForSize(size: Int) = {
    implicit val be = java.nio.ByteOrder.BIG_ENDIAN
    val stringB = new StringBuilder(size)
    val paddingString = "abcdefghijklmnopqrs"

    while (stringB.length() + paddingString.length() < size) stringB.append(paddingString)

    ByteString(stringB.toString().getBytes())
  }

  def sendActionsForBS(bs: ByteString, num: Int) = {
    val mulActs = for (i ← 1 to num) yield (LargerPayloadTestHelper.client <~< bs)
    val ioActs = mulActs.toList.map(_.get).sequence
    ioActs.map(x ⇒ Future.sequence(x))
  }
}

class LargerPayloadSpec extends Specification {
  sequential
  import LargerPayloadTestHelper._

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
        true
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
        true
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
        true
      }
      true
    }
  }
}
