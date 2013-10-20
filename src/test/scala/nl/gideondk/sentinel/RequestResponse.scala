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
import server._
import client._

import scalaz._
import Scalaz._

import akka.actor._
import akka.routing._
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent._

import protocols._

import java.net.InetSocketAddress

class RequestResponseSpec extends WordSpec with ShouldMatchers {
  implicit val duration = Duration(5, SECONDS)

  //def worker(implicit system: ActorSystem) = system.actorOf(Props(new SentinelClientWorker(new InetSocketAddress("localhost", 9999), PingPong.stages, "Worker")(1, 1, 10)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))

  def client(implicit system: ActorSystem) = Client("localhost", 9999, RandomRouter(16), "Worker", 5 seconds, PingPong.stages)(system)

  def server(implicit system: ActorSystem) = SentinelServer(9999, PingPongServerHandler)(PingPong.stages)(system)

  "A client" should {
    //    "return a exception when a request is done when no connection is available" in new TestKitSpec {
    //      val actor = worker
    //      val promise = Promise[PingPongMessageFormat]()
    //      val operation = actor ! Operation(PingPongMessageFormat("PING"), promise)
    //      val result = Try(Await.result(promise.future, 5 seconds))
    //      evaluating { result.get } should produce[SentinelClientWorker.NoConnectionAvailable]
    //    }

    //    "be able to request a response from a server" in new TestKitSpec {
    //      val s = server
    //      val c = client
    //
    //      val action = c <~< PingPongMessageFormat("PING")
    //      action.run.isSuccess
    //    }

    //    "be able to stream multiple requests to a server" in new TestKitSpec {
    //      val s = server
    //      val c = client
    //      
    //      
    //    }

    "be able to ping to the server in timely fashion" in new TestKitSpec {
      val serverSystem = akka.actor.ActorSystem("ping-server-system")
      val clientSystem = akka.actor.ActorSystem("ping-client-system")

      val s = server(serverSystem)
      val c = client(clientSystem)

      val num = 50000

      for (i ← 0 to 10) {
        val mulActs = for (i ← 1 to num) yield c <~< PingPongMessageFormat("PING")
        val tasks = Task.sequenceSuccesses(mulActs.toList)

        val fut = tasks.start
        BenchmarkHelpers.timed("Ping-Ponging " + num + " requests", num) {
          val res = Await.result(fut, Duration.apply(10, scala.concurrent.duration.SECONDS))
          if (res.get.length != num) throw new Exception("Internal benchmark error")
          true
        }

        val res = Await.result(fut, Duration.apply(10, scala.concurrent.duration.SECONDS))
        res.get.filterNot(_ == PingPongMessageFormat("PONG")).length == 0
      }

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
//   val pingServer = SentinelServer.sync(8000, PingPongServerHandler.handle, "Ping Server")(stages)(serverSystem)

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
