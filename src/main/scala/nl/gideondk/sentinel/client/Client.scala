package nl.gideondk.sentinel.client

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{ BidiFlow, GraphDSL, RunnableGraph, Sink, Source, Tcp }
import akka.stream.stage._
import akka.util.ByteString
import akka.{ Done, NotUsed, stream }
import nl.gideondk.sentinel.client.ClientStage.ConnectionEvent
import nl.gideondk.sentinel.{ Command, Event, Processor }

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import ClientStage._
import Client._

object Client {

  trait ClientException

  case class InputQueueClosed() extends Exception with ClientException

  case class InputQueueUnavailable() extends Exception with ClientException

}

class Client[Cmd, Evt](hosts: Source[ConnectionEvent, NotUsed],
                       connectionsPerHost: Int, maximumFailuresPerHost: Int, recoveryPeriod: FiniteDuration,
                       inputBufferSize: Int, inputOverflowStrategy: OverflowStrategy,
                       processor: Processor[Cmd, Evt], protocol: BidiFlow[ByteString, Evt, Cmd, ByteString, Any])(implicit system: ActorSystem, mat: ActorMaterializer) {

  val eventHandler = Sink.foreach[(Try[Event[Evt]], Promise[Event[Evt]])] {
    case (evt, context) ⇒ context.complete(evt)
  }

  val g = RunnableGraph.fromGraph(GraphDSL.create(Source.queue[(Command[Cmd], Promise[Event[Evt]])](inputBufferSize, inputOverflowStrategy)) { implicit b ⇒
    source ⇒
      import GraphDSL.Implicits._

      val s = b.add(new ClientStage[Cmd, Evt](connectionsPerHost, maximumFailuresPerHost, recoveryPeriod, processor, protocol))

      b.add(hosts) ~> s.in0
      source.out ~> s.in1

      s.out ~> b.add(eventHandler)

      ClosedShape
  })

  val input = g.run()

  def request(command: Command[Cmd])(implicit ec: ExecutionContext): Future[Event[Evt]] = {
    val context = Promise[Event[Evt]]()
    input.offer((command, context)).flatMap {
      case QueueOfferResult.Dropped         ⇒ Future.failed(InputQueueUnavailable())
      case QueueOfferResult.QueueClosed     ⇒ Future.failed(InputQueueClosed())
      case QueueOfferResult.Failure(reason) ⇒ Future.failed(reason)
      case QueueOfferResult.Enqueued        ⇒ context.future
    }
  }

}
