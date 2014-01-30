package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.WordSpec
import org.scalatest.matchers.{ Matchers, ShouldMatchers }

import scalaz._
import Scalaz._

import akka.actor._
import akka.routing._
import scala.concurrent.duration._
import scala.concurrent._

import play.api.libs.iteratee._

import protocols._

class RequestResponseSpec extends WordSpec with Matchers {

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

      val action = c ? SimpleCommand(PING_PONG, "")
      val result = action.run

      result.isSuccess should equal(true)
    }

    "be able to requests multiple requests from a server" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val numberOfRequests = 20 * 1000

      val action = Task.sequenceSuccesses(List.fill(numberOfRequests)(c ? SimpleCommand(PING_PONG, "")))
      val result = action.run

      result.get.length should equal(numberOfRequests)
      result.isSuccess should equal(true)
    }

    "be able to receive responses in correct order" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val numberOfRequests = 20 * 1000

      val items = List.range(0, numberOfRequests).map(_.toString)
      val action = Task.sequenceSuccesses(items.map(x ⇒ (c ? SimpleCommand(ECHO, x))))
      val result = action.run.get

      result.map(_.payload) should equal(items)
    }
  }
}