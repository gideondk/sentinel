package nl.gideondk.sentinel.server

import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Deploy, Props, actorRef2Scala }
import akka.io.{ BackpressureBuffer, PipelineContext, PipelineStage, Tcp }
import akka.io.Tcp.{ Bind, Bound, CommandFailed, Connected }
import akka.io.TcpPipelineHandler
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.io.TcpReadWriteAdapter
import akka.util.ByteString

import scala.concurrent.Future

class SentinelServer[Cmd, Evt](port: Int, description: String, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                               requestHandler: ⇒ Init[WithinActorContext, Cmd, Evt] ⇒ ActorRef)(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {
  import context.dispatcher

  val tcp = akka.io.IO(Tcp)(context.system)

  val address = new InetSocketAddress(port)

  override def preStart = {
    tcp ! Bind(self, address)
  }

  def receive = {
    case Bound ⇒
      log.debug(description + " bound to " + address)

    case CommandFailed(cmd) ⇒
      cmd match {
        case x: Bind ⇒
          log.error(description + " failed to bind to " + address)
      }

    case req @ Connected(remoteAddr, localAddr) ⇒
      val init =
        TcpPipelineHandler.withLogger(log,
          stages >>
            new TcpReadWriteAdapter >>
            new BackpressureBuffer(lowBytes, highBytes, maxBufferSize))

      val connection = sender
      val handler = context.actorOf(TcpPipelineHandler.props(init, connection, requestHandler(init)).withDeploy(Deploy.local))
      connection ! Tcp.Register(handler)
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

  def apply[Evt, Cmd](serverPort: Int, handler: Evt ⇒ Future[Cmd], description: String = "Sentinel Server")(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 1024L * 2L, highBytes: Long = 1024L * 1024L, maxBufferSize: Long = 1024L * 1024L * 50L)(implicit system: ActorSystem) = {
      def newHandlerActor(init: Init[WithinActorContext, Cmd, Evt]) = system.actorOf(Props(new SentinelServerBasicAsyncHandler(init, handler)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
    system.actorOf(Props(new SentinelServer(serverPort, description, stages, newHandlerActor)(lowBytes, highBytes, maxBufferSize)))
  }
}

private case object InitializeServerRouter
private case object ReconnectServerRouter

case class NoWorkerException extends Throwable
