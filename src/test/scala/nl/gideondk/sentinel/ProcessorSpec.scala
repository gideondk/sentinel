package nl.gideondk.sentinel

import akka.event.Logging
import akka.stream.{ActorMaterializer, Attributes, ClosedShape}
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import nl.gideondk.sentinel.Command.Ask
import nl.gideondk.sentinel.Registration.SingularResponseRegistration
import nl.gideondk.sentinel.protocol._

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

object ProcessorSpec {

}

class ProcessorSpec extends AkkaSpec {
  val processor = Processor[SimpleMessageFormat, SimpleMessageFormat](SimpleHandler, 1)
  val serverProcessor = Processor[SimpleMessageFormat, SimpleMessageFormat](SimpleServerHandler, 1, true)

  import ProcessorSpec._

  "The AntennaStage" should {
    "correctly flow in a client, server situation" in {
      import SimpleCommand._
      import nl.gideondk.sentinel.protocol.SimpleMessage._

      implicit val materializer = ActorMaterializer()

      val pingCommand = SingularCommand[SimpleMessageFormat](SimpleCommand(PING_PONG, ""))
      val zeroCommand = SingularCommand[SimpleMessageFormat](SimpleCommand(0, ""))

      val source = Source[SingularCommand[SimpleMessageFormat]](List(pingCommand, zeroCommand, pingCommand, zeroCommand))

      val flow = RunnableGraph.fromGraph(GraphDSL.create(Sink.seq[Event[SimpleMessageFormat]]) { implicit b =>
        sink =>
          import GraphDSL.Implicits._

          val client = b.add(processor.flow)
          val server = b.add(serverProcessor.flow.reversed)

          source ~> client.in1
          client.out1 ~> server.in1

          server.out1 ~> b.add(Sink.ignore)
          server.out2 ~> client.in2

          client.out2 ~> sink.in

          Source.empty[SingularCommand[SimpleMessageFormat]] ~> server.in2

          ClosedShape
      })

      Await.result(flow.run(), 5 seconds) shouldBe Vector(SingularEvent(SimpleReply("PONG")), SingularEvent(SimpleReply("PONG")))
    }
  }
}