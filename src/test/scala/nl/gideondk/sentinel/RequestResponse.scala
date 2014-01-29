package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import scalaz._
import Scalaz._

import akka.actor._
import akka.routing._
import scala.concurrent.duration._
import scala.concurrent._

import play.api.libs.iteratee._

import protocols._


class RequestResponseSpec extends WordSpec with ShouldMatchers {

  import SimpleMessage._

  implicit val duration = Duration(5, SECONDS)

  def client(portNumber: Int)(implicit system: ActorSystem) = Client("localhost", portNumber, RandomRouter(16), "Worker", 5 seconds, SimpleMessage.stages, SimpleClientHandler)(system)

  def server(portNumber: Int)(implicit system: ActorSystem) = {
    val s = SentinelServer(portNumber, SimpleServerHandler)(SimpleMessage.stages)(system)
    Thread.sleep(100)
    s
  }

  "A client" should {
    "be able to request a response from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val action = c ? SimpleCommand(PING_PONG_COMMAND, "")
      action.run.isSuccess
    }

    "be able to send a stream to a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val chunks = List.fill(count)(SimpleStreamChunk("ABCDEF")) ++ List(SimpleStreamChunk(""))
      val action = c ?<<-(SimpleCommand(TOTAL_CHUNK_SIZE, ""), Enumerator(chunks: _*))

      val localLength = chunks.foldLeft(0)((b, a) ⇒ b + a.payload.length)
      action.run.isSuccess && action.run.toOption.get.payload.toInt == localLength
    }

    "be able to receive streams from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val action = c ?->> SimpleCommand(GENERATE_NUMBERS, count.toString)

      val stream = action.copoint
      val result = Await.result(stream |>>> Iteratee.getChunks, 5 seconds)
      result.length == count
    }

    "be able to requests multiple requests from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val numberOfRequests = 20 * 1000

      val action = Task.sequenceSuccesses(List.fill(numberOfRequests)(c ? SimpleCommand(PING_PONG_COMMAND, "")))
      val result = action.run
      result.isSuccess && result.get.length == numberOfRequests
    }
  }
}