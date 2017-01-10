package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import nl.gideondk.sentinel.client.{Client, ClientStage, Host}
import nl.gideondk.sentinel.protocol._
import nl.gideondk.sentinel.server.Server

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise, duration}

class ServerClientSpec extends SentinelSpec(ActorSystem()) {
  "a Server and Client" should {
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
    }
  }
}