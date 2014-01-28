package nl.gideondk.sentinel

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.util.Try

import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import akka.io.{ LengthFieldFrame, PipelineContext, SymmetricPipePair, SymmetricPipelineStage }
import akka.routing.RoundRobinRouter
import akka.util.ByteString

import Task._

import scalaz._
import Scalaz._

import akka.actor._
import akka.routing._
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent._

import play.api.libs.iteratee._

import protocols._

import java.net.InetSocketAddress

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
      val action = c ?<<- (SimpleCommand(TOTAL_CHUNK_SIZE, ""), Enumerator(chunks: _*))

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
  }
}

//class MockSentinelClientWorker extends Actor {
//  def receive = {
//    case _ ⇒ ()
//  }
//}
//
//class MockSentinelClientSupervisor(probeWorker: ActorRef) extends SentinelClientSupervisor(new InetSocketAddress("localhost", 9999), RoundRobinRouter(1), "",
//  1 second, new MockSentinelClientWorker()) {
//  override def routerProto = probeWorker
//}

//class

/* 
val promise = Promise[Evt]()
    actor ! Operation(command, promise)
    promise.future


*/
// trait PingPongWorkers {
//   val stages = new PingPongMessageStage >> new LengthFieldFrame(1000)

//   val serverSystem = ActorSystem("ping-server-system")
//   val pingServer = SentinelServer.sync(8000, SimpleServerHandler.handle, "Ping Server")(stages)(serverSystem)

//   val clientSystem = ActorSystem("ping-client-system")
//   val pingClient = SentinelClient.randomRouting("localhost", 8000, 32, "Ping Client")(stages)(clientSystem)
// }

// class PingPongSpec extends Specification with PingPongWorkers {
//   sequential

//   "A client" should {
//     "be able to ping to the server" in {
//       implicit val duration = Duration(10, scala.concurrent.duration.SECONDS)
//       val v = (pingClient <~< PingPongMessageFormat("PING")).run

//       println(v)
//       v == Try(PingPongMessageFormat("PONG"))
//     }

//     "server should disconnect clients on unhandled exceptions" in {
//       implicit val duration = Duration(10, scala.concurrent.duration.SECONDS)
//       val v = (pingClient <~< PingPongMessageFormat("PINGI")).run
//       v.isFailure
//     }

//     "server and client be able to handle multiple concurrent requests" in {
//       val num = 20000

//       val mulActs = for (i ← 1 to num) yield (pingClient <~< PingPongMessageFormat("PING"))
//       val tasks = Task.sequence(mulActs.toList)

//       val fut = tasks.start

//       val res = Await.result(fut, Duration(10, scala.concurrent.duration.SECONDS))
//       res.get.length == num && res.get.filterNot(_ == PingPongMessageFormat("PONG")).length == 0
//     }
//   }

//   step {
//     clientSystem.shutdown()
//     serverSystem.shutdown()
//   }
// }
