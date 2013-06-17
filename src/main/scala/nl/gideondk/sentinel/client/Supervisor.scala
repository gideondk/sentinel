package nl.gideondk.sentinel.client

import java.net.InetSocketAddress
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated, actorRef2Scala }
import akka.io.{ PipelineContext, PipelineStage }
import akka.routing.{ Broadcast, RouterConfig }
import akka.util.ByteString
import nl.gideondk.sentinel._
import akka.routing.RandomRouter
import akka.routing.RoundRobinRouter

class SentinelClient(address: InetSocketAddress, routerConfig: RouterConfig, description: String,
                     reconnectDuration: FiniteDuration, worker: ⇒ Actor) extends Actor with ActorLogging {
  import context.dispatcher
  import SentinelClient._

  var router: Option[ActorRef] = None

  def routerProto = {
    context.system.actorOf(Props(worker).withRouter(routerConfig).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
  }

  def initialize {
    router = Some(routerProto)
    router.get ! Broadcast(SentinelClientWorker.ConnectToHost(address))
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
        case None    ⇒ x.promise.failure(NoConnectionException())
      }

    case x: StreamedOperation[_, _] ⇒
      router match {
        case Some(r) ⇒ r forward x
        case None    ⇒ x.promise.failure(NoConnectionException())
      }
    case _ ⇒
  }
}

object SentinelClient {
  private case object InitializeRouter
  private case object ReconnectRouter

  case class NoConnectionException extends Throwable

  def apply[Cmd, Evt](serverHost: String, serverPort: Int, routerConfig: RouterConfig,
                      description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 1024L * 2L, highBytes: Long = 1024L * 1024L, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) = {
    system.actorOf(Props(new SentinelClient(new InetSocketAddress(serverHost, serverPort), routerConfig, description, workerReconnectTime, new SentinelClientWorker(stages, description + " Worker")(lowBytes, highBytes, maxBufferSize))))
  }

  def randomRouting[Cmd, Evt](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], ackCount: Int = 10, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) =
    apply[Cmd, Evt](serverHost, serverPort, RandomRouter(numberOfWorkers), description, workerReconnectTime)(stages, ackCount, maxBufferSize)

  def roundRobinRouting[Cmd, Evt](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(stages: PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], ackCount: Int = 10, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) =
    apply[Cmd, Evt](serverHost, serverPort, RoundRobinRouter(numberOfWorkers), description, workerReconnectTime)(stages, ackCount, maxBufferSize)
}