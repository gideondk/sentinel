package nl.gideondk.sentinel.server

import scala.concurrent.duration._
import akka.util.ByteString
import akka.io._
import akka.io.Tcp._
import akka.routing._
import akka.actor._
import akka.actor.IO.IterateeRef
import akka.event.Logging
import java.net.InetSocketAddress
import scala.reflect.ClassTag
import scala.concurrent.Future

class SentinelServer(port: Int, routerConfig: RouterConfig, description: String, worker: ⇒ Actor) extends Actor {
  //trait SentinelServer extends Actor {
  import context.dispatcher

  val log = Logging(context.system, this)
  val tcp = akka.io.IO(Tcp)(context.system)

  val address = new InetSocketAddress(port)
  val reinitTime: FiniteDuration = 1 second

  var router: Option[ActorRef] = None

  def routerProto = {
    context.system.actorOf(Props(worker).withRouter(routerConfig))
  }

  def initialize {
    router = Some(routerProto)
    context.watch(router.get)
  }

  override def preStart = {
    tcp ! Bind(self, address)
    self ! InitializeServerRouter
  }

  def receive = {
    case InitializeServerRouter ⇒
      initialize

    case ReconnectServerRouter ⇒
      if (router.isEmpty) initialize

    case Terminated(actor) ⇒
      router = None
      log.debug("Router died, restarting in: " + reinitTime.toString())
      context.system.scheduler.scheduleOnce(reinitTime, self, ReconnectServerRouter)

    case Bound ⇒
      log.debug(description + " bound to " + address)

    case CommandFailed(cmd) ⇒
      cmd match {
        case x: Bind ⇒
          log.error(description + " failed to bind to " + address)
      }

    case req @ Connected(remoteAddr, localAddr) ⇒
      router.get forward req
  }

}

object SentinelServer {

  /** Creates a new SentinelServer
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverPort the port to host on
   *  @param serverRouterConfig Akka router configuration to be used to route the worker actors
   *  @param description description used for logging purposes
   *  @param pipelineCtx the context of type Context used in the pipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel server, hosting on the defined port
   */

  def apply[Evt, Cmd, Context <: PipelineContext](serverPort: Int, serverRouterConfig: RouterConfig,
                                                  handler: Evt ⇒ Future[Cmd], description: String = "Sentinel Server")(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], useWriteAck: Boolean = true)(implicit system: ActorSystem) = {
    system.actorOf(Props(new SentinelServer(serverPort, serverRouterConfig, description, new SentinelServerWorker[Cmd, Evt, Context](pipelineCtx, stages, handler, description + " Worker", useWriteAck))))
  }

  /** Creates a new SentinelServer
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverPort the port to host on
   *  @param numberOfWorkers the amount of worker actors used to represent the server
   *  @param description description used for logging purposespipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel server, hosting on the defined port
   */

  def randomRouting[Evt, Cmd, Context <: PipelineContext](serverPort: Int, numberOfWorkers: Int,
                                                          handler: Evt ⇒ Future[Cmd], description: String = "Sentinel Server")(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], useWriteAck: Boolean = true)(implicit system: ActorSystem) =
    apply(serverPort, RandomRouter(numberOfWorkers), handler, description)(pipelineCtx, stages, useWriteAck)

  /** Creates a new SentinelServer
   *
   *  @tparam Evt event type (type of requests send to server)
   *  @tparam Cmd command type (type of responses to client)
   *  @tparam Context context type used in pipeline
   *  @param serverPort the port to host on
   *  @param numberOfWorkers the amount of worker actors used to represent the server
   *  @param description description used for logging purposespipeline
   *  @param stages the stages used within the pipeline
   *  @param useWriteAck whether to use ack-based flow control or not
   *  @return a new sentinel server, hosting on the defined port
   */

  def roundRobinRouting[Evt, Cmd, Context <: PipelineContext](serverPort: Int, numberOfWorkers: Int,
                                                              handler: Evt ⇒ Future[Cmd], description: String = "Sentinel Server")(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], useWriteAck: Boolean = true)(implicit system: ActorSystem) =
    apply(serverPort, RoundRobinRouter(numberOfWorkers), handler, description)(pipelineCtx, stages, useWriteAck)

}

private case object InitializeServerRouter
private case object ReconnectServerRouter

case class NoWorkerException extends Throwable
