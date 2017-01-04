package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Flow, GraphDSL, RunnableGraph, Sink, Source, Tcp }
import akka.stream.{ ActorMaterializer, ClosedShape, OverflowStrategy }
import akka.util.ByteString
import nl.gideondk.sentinel.client.ClientStage.{ HostEvent, NoConnectionsAvailableException }
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

    val connections = Tcp().bind("localhost", port, halfClose = true)
    val binding = connections.to(handler).run()

    binding.onComplete {
      case Success(b) ⇒ println("Bound to: " + b.localAddress)
      case Failure(e) ⇒
        system.terminate()
    }
  }

  def createCommand(s: String) = {
    (SingularCommand[SimpleMessageFormat](SimpleReply(s)), Promise[Event[SimpleMessageFormat]]())
  }
}

class ClientStageSpec extends SentinelSpec(ActorSystem()) {

  import ClientStageSpec._

  "The ClientStage" should {
    "keep message order intact" in {
      val port = TestHelpers.portNumber.incrementAndGet()
      val server = mockServer(system, port)

      implicit val materializer = ActorMaterializer()

      type Context = Promise[Event[SimpleMessageFormat]]

      val numberOfMessages = 1024

      val messages = (for (i ← 0 to numberOfMessages) yield (createCommand(i.toString))).toList
      val sink = Sink.foreach[(Try[Event[SimpleMessageFormat]], Context)] { case (event, context) ⇒ context.complete(event) }

      val g = RunnableGraph.fromGraph(GraphDSL.create(Source.queue[(Command[SimpleMessageFormat], Promise[Event[SimpleMessageFormat]])](numberOfMessages, OverflowStrategy.backpressure)) { implicit b ⇒
        source ⇒
          import GraphDSL.Implicits._

          val s = b.add(new ClientStage[Context, SimpleMessageFormat, SimpleMessageFormat](32, 8, 2 seconds, true, Processor(SimpleHandler, 1, false), SimpleMessage.protocol.reversed))

          Source.single(ClientStage.HostUp(Host("localhost", port))) ~> s.in2
          source.out ~> s.in1

          s.out1 ~> b.add(sink)
          s.out2 ~> b.add(Sink.ignore)

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

    "handle host up and down events" in {
      val port = TestHelpers.portNumber.incrementAndGet()
      val server = mockServer(system, port)

      implicit val materializer = ActorMaterializer()

      type Context = Promise[Event[SimpleMessageFormat]]

      val hostEvents = Source.queue[HostEvent](10, OverflowStrategy.backpressure)
      val commands = Source.queue[(Command[SimpleMessageFormat], Context)](10, OverflowStrategy.backpressure)
      val events = Sink.queue[(Try[Event[SimpleMessageFormat]], Context)]

      val (hostQueue, commandQueue, eventQueue) = RunnableGraph.fromGraph(GraphDSL.create(hostEvents, commands, events)((_, _, _)) { implicit b ⇒
        (hostEvents, commands, events) ⇒

          import GraphDSL.Implicits._

          val s = b.add(new ClientStage[Context, SimpleMessageFormat, SimpleMessageFormat](1, 8, 2 seconds, true, Processor(SimpleHandler, 1, false), SimpleMessage.protocol.reversed))

          hostEvents ~> s.in2
          commands ~> s.in1

          s.out1 ~> events
          s.out2 ~> b.add(Sink.ignore)

          ClosedShape
      }).run()

      commandQueue.offer(createCommand(""))
      Await.result(eventQueue.pull(), 5 seconds).get._1 shouldEqual Failure(NoConnectionsAvailableException)

      hostQueue.offer(ClientStage.HostUp(Host("localhost", port)))
      Thread.sleep(200)

      commandQueue.offer(createCommand(""))
      Await.result(eventQueue.pull(), 5 seconds).get._1 shouldEqual Success(SingularEvent(SimpleReply("")))

      hostQueue.offer(ClientStage.HostDown(Host("localhost", port)))
      Thread.sleep(200)

      commandQueue.offer(createCommand(""))
      Await.result(eventQueue.pull(), 5 seconds).get._1 shouldEqual Failure(NoConnectionsAvailableException)
    }
  }
}
