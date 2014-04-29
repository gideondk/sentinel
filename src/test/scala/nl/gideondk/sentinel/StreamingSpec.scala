package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import akka.actor._
import akka.routing._
import scala.concurrent.duration._
import scala.concurrent._

import scala.util.Try
import play.api.libs.iteratee._

import protocols._

class StreamingSpec extends WordSpec {

  import SimpleMessage._

  implicit val duration = Duration(5, SECONDS)

  def client(portNumber: Int)(implicit system: ActorSystem) = Client.randomRouting("localhost", portNumber, 2, "Worker", SimpleMessage.stages, 0.5 seconds, SimpleServerHandler)(system)

  def server(portNumber: Int)(implicit system: ActorSystem) = {
    val s = Server(portNumber, SimpleServerHandler, stages = SimpleMessage.stages)(system)
    Thread.sleep(100)
    s
  }

  "A client" should {
    "be able to send a stream to a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val chunks = List.fill(count)(SimpleStreamChunk("ABCDEF")) ++ List(SimpleStreamChunk(""))
      val action = c ?<<- (SimpleCommand(TOTAL_CHUNK_SIZE, ""), Enumerator(chunks: _*))

      val localLength = chunks.foldLeft(0)((b, a) ⇒ b + a.payload.length)

      val result = Try(Await.result(action, 5 seconds))

      result.isSuccess should equal(true)
      result.get.payload.toInt should equal(localLength)
    }

    "be able to receive streams from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val action = c ?->> SimpleCommand(GENERATE_NUMBERS, count.toString)

      val f = action.flatMap(_ |>>> Iteratee.getChunks)
      val result = Await.result(f, 5 seconds)

      result.length should equal(count)
    }

    "be able to receive multiple streams simultaneously from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val numberOfActions = 8
      val actions = Future.sequence(List.fill(numberOfActions)((c ?->> SimpleCommand(GENERATE_NUMBERS, count.toString)).flatMap(x ⇒ x |>>> Iteratee.getChunks)))

      val result = Await.result(actions.map(_.flatten), 5 seconds)

      result.length should equal(count * numberOfActions)
    }

    "be able to receive multiple streams and normal commands simultaneously from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val numberOfActions = 8

      val streamAction = Future.sequence(List.fill(numberOfActions)((c ?->> SimpleCommand(GENERATE_NUMBERS, count.toString)).flatMap(x ⇒ x |>>> Iteratee.getChunks)))
      val action = Future.sequence(List.fill(count)(c ? SimpleCommand(PING_PONG, "")))

      val actions = Future.sequence(List(streamAction, action))

      val result = Try(Await.result(actions.map(_.flatten), 5 seconds))

      result.isSuccess should equal(true)
    }

    "be able to receive send streams simultaneously to a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val count = 500
      val chunks = List.fill(count)(SimpleStreamChunk("ABCDEF")) ++ List(SimpleStreamChunk(""))
      val action = c ?<<- (SimpleCommand(TOTAL_CHUNK_SIZE, ""), Enumerator(chunks: _*))

      val numberOfActions = 8
      val actions = Future.sequence(List.fill(numberOfActions)(c ?<<- (SimpleCommand(TOTAL_CHUNK_SIZE, ""), Enumerator(chunks: _*))))

      val localLength = chunks.foldLeft(0)((b, a) ⇒ b + a.payload.length)
      val result = Await.result(actions, 5 seconds)

      result.map(_.payload.toInt).sum should equal(localLength * numberOfActions)
    }
  }
}
