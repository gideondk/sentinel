package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.WordSpec

import akka.actor._
import scala.concurrent.duration._
import scala.concurrent._

import scala.util.Try

import protocols._

class RequestResponseSpec extends WordSpec {

  import SimpleMessage._

  implicit val duration = Duration(5, SECONDS)

  def client(portNumber: Int)(implicit system: ActorSystem) = Client.roundRobinRouting("localhost", portNumber, 16, "Worker", SimpleMessage.stages, 0.1 seconds, SimpleServerHandler, lowBytes = 1024L, highBytes = 1024 * 1024, maxBufferSize = 1024 * 1024 * 50)(system)

  def server(portNumber: Int)(implicit system: ActorSystem) = {
    val s = Server(portNumber, SimpleServerHandler, stages = SimpleMessage.stages)(system)
    Thread.sleep(100)
    s
  }

  "A client" should {
    "be able to request a response from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val action = c ? SimpleCommand(PING_PONG, "")
      val result = Try(Await.result(action, 5 seconds))

      result.isSuccess should equal(true)
    }

    "be able to requests multiple requests from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)
      Thread.sleep(100)

      val numberOfRequests = 1000

      val action = Future.sequence(List.fill(numberOfRequests)(c ? SimpleCommand(ECHO, LargerPayloadTestHelper.randomBSForSize(1024 * 10))))
      val result = Try(Await.result(action, 5 seconds))

      result.get.length should equal(numberOfRequests)
      result.isSuccess should equal(true)
    }

    "be able to receive responses in correct order" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val numberOfRequests = 20 * 1000

      val items = List.range(0, numberOfRequests).map(_.toString)
      val action = Future.sequence(items.map(x ⇒ (c ? SimpleCommand(ECHO, x))))
      val result = Await.result(action, 5 seconds)

      result.map(_.payload) should equal(items)
    }

    "should automatically reconnect" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)
      Thread.sleep(500)

      val action = c ? SimpleCommand(PING_PONG, "")
      val result = Try(Await.result(action, 5 seconds))

      result.isSuccess should equal(true)

      system.stop(s.actor)
      Thread.sleep(250)

      val secAction = c ? SimpleCommand(PING_PONG, "")
      val ss = server(portNumber)

      Thread.sleep(250)
      val endResult = Try(Await.result(secAction, 10 seconds))

      endResult.isSuccess should equal(true)
    }
  }
}
