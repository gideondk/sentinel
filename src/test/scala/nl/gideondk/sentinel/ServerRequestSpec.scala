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
import akka.util.Timeout

class ServerRequestSpec extends WordSpec with ShouldMatchers {

  import SimpleMessage._

  implicit val duration = Duration(5, SECONDS)
  implicit val timeout = Timeout(Duration(5, SECONDS))

  val numberOfConnections = 16

  def client(portNumber: Int)(implicit system: ActorSystem) = Client.randomRouting("localhost", portNumber, numberOfConnections, "Worker", SimpleMessage.stages, 5 seconds, SimpleServerHandler)(system)

  def server(portNumber: Int)(implicit system: ActorSystem) = {
    val s = SentinelServer(portNumber, SimpleServerHandler, stages = SimpleMessage.stages)(system)
    Thread.sleep(100)
    s
  }

  "A server" should {
    "be able to send a request to a client" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)
      val c = client(portNumber)
      Thread.sleep(500)

      val action = (s ? SimpleCommand(PING_PONG, ""))
      val result = action.copoint

      result should equal(SimpleReply("PONG"))
    }

    "be able to send a request to a all unique connected hosts" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)

      val numberOfClients = 5
      List.fill(numberOfClients)(client(portNumber))

      Thread.sleep(500)

      val action = (s ?* SimpleCommand(PING_PONG, ""))
      val result = action.copoint

      result.length should equal(1)
    }

    "be able to send a request to a all connected clients" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)

      val numberOfClients = 5
      List.fill(numberOfClients)(client(portNumber))

      Thread.sleep(500)

      val action = (s ?** SimpleCommand(PING_PONG, ""))
      val result = action.copoint

      result.length should equal(numberOfClients * numberOfConnections)
    }

    "be able to retrieve the correct number of connected sockets" in new TestKitSpec {
      val portNumber = TestHelpers.portNumber.getAndIncrement()
      val s = server(portNumber)

      val numberOfClients = 5
      val clients = List.fill(numberOfClients)(client(portNumber))

      Thread.sleep(500)

      val connectedSockets = (s connectedSockets).copoint
      connectedSockets should equal(numberOfClients * numberOfConnections)

      val connectedHosts = (s connectedHosts).copoint
      connectedHosts should equal(1)

      val toBeKilledActors = clients.splitAt(3)._1.map(_.actor)
      toBeKilledActors.foreach(x ⇒ x ! PoisonPill)
      Thread.sleep(500)

      val stillConnectedSockets = (s connectedSockets).copoint
      stillConnectedSockets should equal(2 * numberOfConnections)
    }
  }
}