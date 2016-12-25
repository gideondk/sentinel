package nl.gideondk.sentinel.client

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{ BidiFlow, Flow, GraphDSL, RunnableGraph, Sink, Source, Tcp }
import akka.stream.stage._
import akka.util.ByteString
import akka.{ Done, NotUsed, stream }
import nl.gideondk.sentinel.client.ClientStage.ConnectionEvent

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import ClientStage._
import Client._
import nl.gideondk.sentinel.Config
import nl.gideondk.sentinel.pipeline.{ Processor, Resolver }
import nl.gideondk.sentinel.protocol._

object ClientConfig {

  import com.typesafe.config.ConfigFactory

  private lazy val config = ConfigFactory.load().getConfig("sentinel")

  val connectionsPerHost = config.getInt("client.host.max-connections")
  val maxFailuresPerHost = config.getInt("client.host.max-failures")
  val failureRecoveryPeriod = Duration(config.getDuration("client.host.failure-recovery-duration").toNanos, TimeUnit.NANOSECONDS)

  val inputBufferSize = config.getInt("client.input-buffer-size")
}

object Client {

  trait ClientException

  case class InputQueueClosed() extends Exception with ClientException

  case class InputQueueUnavailable() extends Exception with ClientException

  def apply[Cmd, Evt](hosts: Source[ConnectionEvent, NotUsed], resolver: Resolver[Evt],
                      shouldReact: Boolean, inputOverflowStrategy: OverflowStrategy,
                      protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext): Client[Cmd, Evt] = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)
    new Client(hosts, ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, ClientConfig.inputBufferSize, inputOverflowStrategy, processor, protocol.reversed)
  }

  def apply[Cmd, Evt](hosts: List[Host], resolver: Resolver[Evt],
                      shouldReact: Boolean, inputOverflowStrategy: OverflowStrategy,
                      protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext): Client[Cmd, Evt] = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)
    new Client(Source(hosts.map(LinkUp)), ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, ClientConfig.inputBufferSize, inputOverflowStrategy, processor, protocol.reversed)
  }

  def flow[Cmd, Evt](hosts: Source[ConnectionEvent, NotUsed], resolver: Resolver[Evt],
                     shouldReact: Boolean = false, inputOverflowStrategy: OverflowStrategy,
                     protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext) = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)
    val client = new Client(hosts, ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, ClientConfig.inputBufferSize, inputOverflowStrategy, processor, protocol.reversed)
    Flow[Command[Cmd]].mapAsync(1)(cmd ⇒ client.send(cmd))
  }

  def rawFlow[Context, Cmd, Evt](hosts: Source[ConnectionEvent, NotUsed], resolver: Resolver[Evt],
                                 shouldReact: Boolean = false,
                                 protocol: BidiFlow[Cmd, ByteString, ByteString, Evt, Any])(implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext) = {
    val processor = Processor[Cmd, Evt](resolver, Config.producerParallelism)

    Flow.fromGraph(GraphDSL.create(hosts) { implicit b ⇒
      connections ⇒
        import GraphDSL.Implicits._

        val s = b.add(new ClientStage[Context, Cmd, Evt](ClientConfig.connectionsPerHost, ClientConfig.maxFailuresPerHost, ClientConfig.failureRecoveryPeriod, processor, protocol.reversed))
        connections ~> s.in0
        FlowShape(s.in1, s.out)
    })
  }
}

class Client[Cmd, Evt](hosts: Source[ConnectionEvent, NotUsed],
                       connectionsPerHost: Int, maximumFailuresPerHost: Int, recoveryPeriod: FiniteDuration,
                       inputBufferSize: Int, inputOverflowStrategy: OverflowStrategy,
                       processor: Processor[Cmd, Evt], protocol: BidiFlow[ByteString, Evt, Cmd, ByteString, Any])(implicit system: ActorSystem, mat: ActorMaterializer) {

  type Context = Promise[Event[Evt]]

  val eventHandler = Sink.foreach[(Try[Event[Evt]], Promise[Event[Evt]])] {
    case (evt, context) ⇒ context.complete(evt)
  }

  val g = RunnableGraph.fromGraph(GraphDSL.create(Source.queue[(Command[Cmd], Promise[Event[Evt]])](inputBufferSize, inputOverflowStrategy)) { implicit b ⇒
    source ⇒
      import GraphDSL.Implicits._

      val s = b.add(new ClientStage[Context, Cmd, Evt](connectionsPerHost, maximumFailuresPerHost, recoveryPeriod, processor, protocol))

      b.add(hosts) ~> s.in0
      source.out ~> s.in1

      s.out ~> b.add(eventHandler)

      ClosedShape
  })

  val input = g.run()

  def send(command: Command[Cmd])(implicit ec: ExecutionContext): Future[Event[Evt]] = {
    val context = Promise[Event[Evt]]()
    input.offer((command, context)).flatMap {
      case QueueOfferResult.Dropped         ⇒ Future.failed(InputQueueUnavailable())
      case QueueOfferResult.QueueClosed     ⇒ Future.failed(InputQueueClosed())
      case QueueOfferResult.Failure(reason) ⇒ Future.failed(reason)
      case QueueOfferResult.Enqueued        ⇒ context.future
    }
  }

}
