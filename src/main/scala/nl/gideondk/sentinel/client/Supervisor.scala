package nl.gideondk.sentinel.client

import java.net.InetSocketAddress
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated, actorRef2Scala }
import akka.io.{ PipelineContext, PipelineStage }
import akka.routing.{ Broadcast, RouterConfig }
import akka.util.ByteString
import nl.gideondk.sentinel._

import akka.routing._
import scala.concurrent.Promise
import play.api.libs.iteratee.Enumerator

trait SentinelClient[Cmd, Evt] {
  def actor: ActorRef

  def <~<(command: Cmd): Task[Evt] = sendCommand(command)

  def sendCommand(command: Cmd): Task[Evt] = Task {
    val promise = Promise[Evt]()
    actor ! Operation(command, promise)
    promise.future
  }

  def streamCommands(stream: Enumerator[Cmd]): Task[Evt] = Task {
    val promise = Promise[Evt]()
    actor ! StreamedOperation(stream, promise)
    promise.future
  }
}

class SentinelClientSupervisor(address: InetSocketAddress, routerConfig: RouterConfig, description: String,
                               reconnectDuration: FiniteDuration, worker: ⇒ Actor) extends Actor with ActorLogging {
  import context.dispatcher

  private case object InitializeRouter
  private case object ReconnectRouter

  var router: Option[ActorRef] = None

  def routerProto = {
    context.system.actorOf(Props(worker).withRouter(routerConfig).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
  }

  def initialize {
    router = Some(routerProto)
    context.watch(router.get)
  }

  override def preStart = {
    self ! InitializeRouter
  }

  def receive = {
    case InitializeRouter ⇒
      initialize

    case ReconnectRouter ⇒
      if (router.isEmpty) initialize

    case Terminated(actor) ⇒
      /* If router died, restart after a period of time */
      router = None
      log.debug("Router died, restarting in: " + reconnectDuration.toString())
      context.system.scheduler.scheduleOnce(reconnectDuration, self, ReconnectRouter)

    case x: Operation[_, _] ⇒
      router match {
        case Some(r) ⇒ r forward x
        case None    ⇒ x.promise.failure(NoConnectionException("No connection available for: " + address))
      }

    case x: StreamedOperation[_, _] ⇒
      router match {
        case Some(r) ⇒ r forward x
        case None    ⇒ x.promise.failure(NoConnectionException("No connection available for: " + address))
      }
    case _ ⇒
  }
}

case class NoConnectionException(msg: String) extends Throwable(msg)

object SentinelClient {
  def apply[Cmd, Evt](serverHost: String, serverPort: Int, routerConfig: RouterConfig,
                      description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 1024L * 2L, highBytes: Long = 1024L * 1024L, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) = {
    new SentinelClient[Cmd, Evt] {
      val actor = system.actorOf(Props(new SentinelClientSupervisor(new InetSocketAddress(serverHost, serverPort), routerConfig, description, workerReconnectTime, new SentinelClientWorker(new InetSocketAddress(serverHost, serverPort), stages, description + " Worker")(lowBytes, highBytes, maxBufferSize))))
    }
  }

  def waiting[Cmd, Evt](serverHost: String, serverPort: Int, routerConfig: RouterConfig,
                        description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 1024L * 2L, highBytes: Long = 1024L * 1024L, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) = {
    new SentinelClient[Cmd, Evt] {
      val actor = system.actorOf(Props(new SentinelClientSupervisor(new InetSocketAddress(serverHost, serverPort), routerConfig, description, workerReconnectTime, new WaitingSentinelClientWorker(new InetSocketAddress(serverHost, serverPort), stages, description + " Worker")(lowBytes, highBytes, maxBufferSize))))
    }
  }

  def randomRouting[Cmd, Evt](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], ackCount: Int = 10, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) =
    apply[Cmd, Evt](serverHost, serverPort, RandomRouter(numberOfWorkers), description, workerReconnectTime)(stages, ackCount, maxBufferSize)

  def roundRobinRouting[Cmd, Evt](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], ackCount: Int = 10, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) =
    apply[Cmd, Evt](serverHost, serverPort, RoundRobinRouter(numberOfWorkers), description, workerReconnectTime)(stages, ackCount, maxBufferSize)

  def dynamic[Cmd, Evt](serverHost: String, serverPort: Int, lowerBound: Int = 2, upperBound: Int = 16, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], ackCount: Int = 10, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) = {
    val resizer = DefaultResizer(lowerBound, upperBound)
    apply[Cmd, Evt](serverHost, serverPort, RoundRobinRouter(resizer = Some(resizer)), description, workerReconnectTime)(stages, ackCount, maxBufferSize)
  }
}
