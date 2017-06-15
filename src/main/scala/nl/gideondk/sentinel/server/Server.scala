package nl.gideondk.sentinel.server

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ BidiFlow, Flow, GraphDSL, Sink, Source, Tcp }
import akka.stream.{ Materializer, FlowShape }
import akka.util.ByteString
import nl.gideondk.sentinel.pipeline.{ Processor, Resolver }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

object Server {
  def apply[Cmd, Evt](interface: String, port: Int, resolver: Resolver[Evt], protocol: BidiFlow[ByteString, Evt, Cmd, ByteString, Any])(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Unit = {

    val handler = Sink.foreach[Tcp.IncomingConnection] { conn ⇒
      val processor = Processor[Cmd, Evt](resolver, 1, true)

      val flow = Flow.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._

        val pipeline = b.add(processor.flow.atop(protocol.reversed))

        pipeline.in1 <~ Source.empty
        pipeline.out2 ~> Sink.ignore

        FlowShape(pipeline.in2, pipeline.out1)
      })

      conn handleWith flow
    }

    val connections = Tcp().bind(interface, port, halfClose = true)
    val binding = connections.to(handler).run()

    binding.onComplete {
      case Success(b) ⇒ println("Bound to: " + b.localAddress)
      case Failure(e) ⇒
        system.terminate()
    }

    binding
  }
}
