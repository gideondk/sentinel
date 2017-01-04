package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ClosedShape, OverflowStrategy }
import akka.stream.scaladsl.{ GraphDSL, RunnableGraph, Sink, Source }
import nl.gideondk.sentinel.client.ClientStage.NoConnectionsAvailableException
import nl.gideondk.sentinel.client.{ Client, ClientStage, Host }
import nl.gideondk.sentinel.pipeline.Processor
import nl.gideondk.sentinel.protocol._

import scala.concurrent.{ Await, Promise, duration }
import duration._
import scala.util.{ Failure, Success, Try }

class ClientSpec extends SentinelSpec(ActorSystem()) {
  "a Client" should {
    "keep message order intact" in {
      val port = TestHelpers.portNumber.incrementAndGet()
      val server = ClientStageSpec.mockServer(system, port)
      implicit val materializer = ActorMaterializer()

      val numberOfMessages = 100

      val messages = (for (i ← 0 to numberOfMessages) yield (SingularCommand[SimpleMessageFormat](SimpleReply(i.toString)))).toList
      val sink = Sink.foreach[(Try[Event[SimpleMessageFormat]], Promise[Event[SimpleMessageFormat]])] { case (event, context) ⇒ context.complete(event) }

      val client = Client.flow(Source.single(ClientStage.HostUp(Host("localhost", port))), SimpleHandler, false, SimpleMessage.protocol)
      val results = Source(messages).via(client).runWith(Sink.seq)

      whenReady(results) { result ⇒
        result should equal(messages.map(x ⇒ SingularEvent(x.payload)))
      }
    }

    "handle connection issues" in {
      val port = TestHelpers.portNumber.incrementAndGet()
      val serverSystem = ActorSystem()
      ClientStageSpec.mockServer(serverSystem, port)

      implicit val materializer = ActorMaterializer()

      type Context = Promise[Event[SimpleMessageFormat]]

      val client = Client(Source.single(ClientStage.HostUp(Host("localhost", port))), SimpleHandler, false, OverflowStrategy.backpressure, SimpleMessage.protocol)

      Await.result(client.ask(SimpleReply("1")), 5 seconds) shouldEqual (SimpleReply("1"))

      serverSystem.terminate()
      Thread.sleep(100)

      Try(Await.result(client.ask(SimpleReply("1")), 5 seconds)) shouldEqual (Failure(NoConnectionsAvailableException))

      ClientStageSpec.mockServer(system, port)
      Thread.sleep(3000)

      Await.result(client.ask(SimpleReply("1")), 5 seconds) shouldEqual (SimpleReply("1"))
    }
  }
}