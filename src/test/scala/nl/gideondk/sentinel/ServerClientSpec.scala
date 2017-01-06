package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ClosedShape, OverflowStrategy }
import akka.stream.scaladsl.{ GraphDSL, RunnableGraph, Sink, Source }
import nl.gideondk.sentinel.client.ClientStage.NoConnectionsAvailableException
import nl.gideondk.sentinel.client.{ Client, ClientStage, Host }
import nl.gideondk.sentinel.pipeline.Processor
import nl.gideondk.sentinel.protocol._
import nl.gideondk.sentinel.server.Server

import scala.concurrent.{ Await, Promise, duration }
import duration._
import scala.util.{ Failure, Success, Try }

class ServerClientSpec extends SentinelSpec(ActorSystem()) {
  "a Server and Client" should {
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

    "correctly handle asymmetrical message types in a client, server situation" in {
      import nl.gideondk.sentinel.protocol.SimpleMessage._

      val port = TestHelpers.portNumber.incrementAndGet()
      implicit val materializer = ActorMaterializer()

      type Context = Promise[Event[SimpleMessageFormat]]

      val server = Server("localhost", port, SimpleServerHandler, SimpleMessage.protocol.reversed)
      val client = Client(Source.single(ClientStage.HostUp(Host("localhost", port))), SimpleHandler, false, OverflowStrategy.backpressure, SimpleMessage.protocol)

      val pingCommand = SimpleCommand(PING_PONG, "")
      val generateNumbersCommand = SimpleCommand(GENERATE_NUMBERS, "1024")
      val sendStream = Source.single(SimpleCommand(TOTAL_CHUNK_SIZE, "")) ++ Source(List.fill(1024)(SimpleStreamChunk("A"))) ++ Source.single(SimpleStreamChunk(""))

      Await.result(client.ask(pingCommand), 5 seconds) shouldBe SimpleReply("PONG")
      Await.result(client.sendStream(sendStream), 5 seconds) shouldBe SimpleReply("1024")
      Await.result(client.ask(pingCommand), 5 seconds) shouldBe SimpleReply("PONG")
      Await.result(client.askStream(generateNumbersCommand).flatMap(x ⇒ x.runWith(Sink.seq)), 5 seconds) shouldBe (for (i ← 0 until 1024) yield (SimpleStreamChunk(i.toString)))

      //      Await.result(flow.run(), 5 seconds)
      //      whenReady(flow.run()) { result ⇒
      //        result should equal(Seq(SingularEvent(SimpleReply("PONG")), SingularEvent(SimpleReply("PONG"))))
      //      }
    }
  }
}

//Server