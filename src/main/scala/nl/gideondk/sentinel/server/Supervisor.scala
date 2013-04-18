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

class SentinelServer(port: Int, description: String, worker: ⇒ Actor) extends Actor {
  import context.dispatcher

  val log = Logging(context.system, this)
  val tcp = akka.io.IO(Tcp)(context.system)

  val address = new InetSocketAddress(port)
  val reinitTime: FiniteDuration = 1 second

  override def preStart = {
    tcp ! Bind(self, address)
    self ! InitializeServerRouter
  }

  def receive = {
    case Terminated(actor) ⇒
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
      context.system.actorOf(Props(worker)) forward req
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

  def apply[Evt, Cmd, Context <: PipelineContext](serverPort: Int, handler: Evt ⇒ Future[Cmd], description: String = "Sentinel Server")(pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], ackCount: Int = 10, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) = {
    system.actorOf(Props(new SentinelServer(serverPort, description, new SentinelServerWorker[Cmd, Evt, Context](pipelineCtx, stages, handler, description + " Worker", ackCount, maxBufferSize))))
  }
}

private case object InitializeServerRouter
private case object ReconnectServerRouter

case class NoWorkerException extends Throwable
