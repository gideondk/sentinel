package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import akka.actor._
import akka.routing._

import scala.concurrent._
import scala.concurrent.duration._

import protocols._

class FullDuplexSpec extends WordSpec with ShouldMatchers {

  import SimpleMessage._

  implicit val duration = Duration(25, SECONDS)

  def client(portNumber: Int)(implicit system: ActorSystem) = Client.randomRouting("localhost", portNumber, 1, "Worker", SimpleMessage.stages, 0.5 seconds, SimpleServerHandler)(system)

  def server(portNumber: Int)(implicit system: ActorSystem) = {
    val s = Server(portNumber, SimpleServerHandler, stages = SimpleMessage.stages)(system)
    s
  }

  "A client and a server" should {
    "be able to exchange requests simultaneously" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      Thread.sleep(500)
      val action = c ? SimpleCommand(PING_PONG, "")
      val serverAction = (s ?* SimpleCommand(PING_PONG, "")).map(_.head)

      val responses = Future.sequence(List(action, serverAction))

      val results = Await.result(responses, 5 seconds)

      results.length should equal(2)
      results.distinct.length should equal(1)
    }

    "be able to exchange multiple requests simultaneously" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      Thread.sleep(1000)

      val c = client(portNumber)
      val secC = client(portNumber)
      Thread.sleep(1000)

      val numberOfRequests = 10

      val actions = Future.sequence(List.fill(numberOfRequests)(c ? SimpleCommand(PING_PONG, "")))
      val secActions = Future.sequence(List.fill(numberOfRequests)(secC ? SimpleCommand(PING_PONG, "")))
      val serverActions = Future.sequence(List.fill(numberOfRequests)((s ?** SimpleCommand(PING_PONG, ""))))

      val combined = Future.sequence(List(actions, serverActions.map(_.flatten), secActions))

      val aa = Await.result(actions, 5 seconds)

      val results = Await.result(combined, 5 seconds)

      results(0).length should equal(numberOfRequests)
      results(2).length should equal(numberOfRequests)
      results(1).length should equal(numberOfRequests * 2)
    }
  }
}
