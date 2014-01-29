package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import scalaz._
import Scalaz._

import akka.actor._
import akka.routing._
import scala.concurrent.duration._

import protocols._

class FullDuplexSpec extends WordSpec with ShouldMatchers {

  import SimpleMessage._

  implicit val duration = Duration(25, SECONDS)

  def client(portNumber: Int)(implicit system: ActorSystem) = Client("localhost", portNumber, RandomRouter(16), "Worker", 5 seconds, SimpleMessage.stages, SimpleServerHandler)(system)

  def server(portNumber: Int)(implicit system: ActorSystem) = {
    val s = SentinelServer(portNumber, SimpleServerHandler)(SimpleMessage.stages)(system)
    Thread.sleep(100)
    s
  }

  "A client and a server" should {
    "be able to exchange requests simultaneously" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)

      val action = c ? SimpleCommand(PING_PONG_COMMAND, "")
      val serverAction = (s ?* SimpleCommand(PING_PONG_COMMAND, "")).map(_.head)

      val responses = Task.sequence(List(action, serverAction))

      val results = responses.run.toOption.get
      results.length == 2 && results.distinct.length == 1
    }

    "be able to exchange multiple requests simultaneously" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)
      val secC = client(portNumber)

      val numberOfRequests = 1000

      val actions = Task.sequenceSuccesses(List.fill(numberOfRequests)(c ? SimpleCommand(PING_PONG_COMMAND, "")))
      val secActions = Task.sequenceSuccesses(List.fill(numberOfRequests)(secC ? SimpleCommand(PING_PONG_COMMAND, "")))
      val serverActions = Task.sequenceSuccesses(List.fill(numberOfRequests)((s ?* SimpleCommand(PING_PONG_COMMAND, ""))))

      val combined = Task.sequence(List(actions, serverActions.map(_.flatten), secActions))

      val results = combined.run.get

      results(0).length == numberOfRequests && results(1).length == numberOfRequests * 2 && results(2).length == numberOfRequests
    }
  }
}