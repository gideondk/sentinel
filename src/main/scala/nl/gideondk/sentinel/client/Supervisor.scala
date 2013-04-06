package nl.gideondk.sentinel.client

import java.net.InetSocketAddress

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

import akka.actor._
import akka.event.Logging
import akka.routing._

import akka.io._

import akka.util.ByteString
import nl.gideondk.sentinel._

class SentinelClient(address: InetSocketAddress, routerConfig: RouterConfig, description: String,
                     reconnectDuration: FiniteDuration, worker: ⇒ Actor) extends Actor {
  import context.dispatcher
  import SentinelClient._

  val log = Logging(context.system, this)
  var router: Option[ActorRef] = None

  def routerProto = {
    context.system.actorOf(Props(worker).withRouter(routerConfig))
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

    case _ ⇒
  }
}

object SentinelClient {

  private case object InitializeRouter
  private case object ReconnectRouter

  case class NoConnectionException extends Throwable

  /** Creates a new SentinelClient
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverHost the host to connect to
   *  @param serverPort the port to to connect to
   *  @param routerConfig Akka router configuration to be used to route the worker actors
   *  @param description description used for logging purposes
   *  @param workerReconnectTime the amount of time a client tries to reconnect after disconnection
   *  @param pipelineCtx the context of type Context used in the pipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel client, connected on the defined host and port
   */

  def apply[Cmd, Evt, Context <: PipelineContext](serverHost: String, serverPort: Int, routerConfig: RouterConfig,
                                                  description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], useWriteAck: Boolean = true)(implicit system: ActorSystem) = {
    system.actorOf(Props(new SentinelClient(new InetSocketAddress(serverHost, serverPort), routerConfig, description, workerReconnectTime, new SentinelClientWorker[Cmd, Evt, Context](pipelineCtx, stages, description + " Worker", useWriteAck))))
  }

  /** Creates a new SentinelClient
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverHost the host to connect to
   *  @param serverPort the port to to connect to
   *  @param numberOfWorkers the amount of worker actors used to connect to the server
   *  @param description description used for logging purposes
   *  @param workerReconnectTime the amount of time a client tries to reconnect after disconnection
   *  @param pipelineCtx the context of type Context used in the pipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel client, connected on the defined host and port
   */

  def randomRouting[Cmd, Evt, Context <: PipelineContext](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], useWriteAck: Boolean = true)(implicit system: ActorSystem) =
    apply[Cmd, Evt, Context](serverHost, serverPort, RandomRouter(numberOfWorkers), description, workerReconnectTime)(pipelineCtx, stages, useWriteAck)

  /** Creates a new SentinelClient
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverHost the host to connect to
   *  @param serverPort the port to to connect to
   *  @param numberOfWorkers the amount of worker actors used to connect to the server
   *  @param description description used for logging purposes
   *  @param workerReconnectTime the amount of time a client tries to reconnect after disconnection
   *  @param pipelineCtx the context of type Context used in the pipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel client, connected on the defined host and port
   */

  def roundRobinRouting[Cmd, Evt, Context <: PipelineContext](serverHost: String, serverPort: Int, numberOfWorkers: Int, description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds)(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], useWriteAck: Boolean = true)(implicit system: ActorSystem) =
    apply[Cmd, Evt, Context](serverHost, serverPort, RoundRobinRouter(numberOfWorkers), description, workerReconnectTime)(pipelineCtx, stages, useWriteAck)

}