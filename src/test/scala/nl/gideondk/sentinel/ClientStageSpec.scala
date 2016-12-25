package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Flow, GraphDSL, RunnableGraph, Sink, Source, Tcp }
import akka.stream.{ ActorMaterializer, ClosedShape, OverflowStrategy }
import akka.util.ByteString
import nl.gideondk.sentinel.client.{ ClientStage, Host }
import nl.gideondk.sentinel.pipeline.Processor
import nl.gideondk.sentinel.protocol._

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

object ClientStageSpec {
  def mockServer(system: ActorSystem, port: Int): Unit = {
    implicit val sys = system
    import system.dispatcher
    implicit val materializer = ActorMaterializer()

    val handler = Sink.foreach[Tcp.IncomingConnection] { conn ⇒
      conn handleWith Flow[ByteString]
    }

    val connections = Tcp().bind("localhost", port)
    val binding = connections.to(handler).run()

    binding.onComplete {
      case Success(b) ⇒
        println("Server started, listening on: " + b.localAddress)
      case Failure(e) ⇒
        println(s"Server could not bind to localhost:$port: ${e.getMessage}")
        system.terminate()
    }
  }
}

class ClientStageSpec extends SentinelSpec(ActorSystem()) {

  import ClientStageSpec._

  "The ClientStage" should {
    "keep message order intact" in {
      val server = mockServer(system, 9000)
      implicit val materializer = ActorMaterializer()

      type Context = Promise[Event[SimpleMessageFormat]]

      val numberOfMessages = 1024

      val messages = (for (i ← 0 to numberOfMessages) yield (SingularCommand[SimpleMessageFormat](SimpleReply(i.toString)), Promise[Event[SimpleMessageFormat]]())).toList
      val sink = Sink.foreach[(Try[Event[SimpleMessageFormat]], Promise[Event[SimpleMessageFormat]])] { case (event, context) ⇒ context.complete(event) }

      val g = RunnableGraph.fromGraph(GraphDSL.create(Source.queue[(Command[SimpleMessageFormat], Promise[Event[SimpleMessageFormat]])](numberOfMessages, OverflowStrategy.backpressure)) { implicit b ⇒
        source ⇒
          import GraphDSL.Implicits._

          val s = b.add(new ClientStage[Context, SimpleMessageFormat, SimpleMessageFormat](32, 8, 2 seconds, Processor(SimpleHandler, 1, false), SimpleMessage.protocol.reversed))

          Source.single(ClientStage.LinkUp(Host("localhost", 9000))) ~> s.in0
          source.out ~> s.in1

          s.out ~> b.add(sink)

          ClosedShape
      })

      val sourceQueue = g.run()
      messages.foreach(sourceQueue.offer)
      val results = Future.sequence(messages.map(_._2.future))

      whenReady(results) { result ⇒
        sourceQueue.complete()
        result should equal(messages.map(x ⇒ SingularEvent(x._1.payload)))
      }
    }
  }
}
