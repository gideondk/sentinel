package nl.gideondk.sentinel

import java.net.InetSocketAddress

import akka.actor._
import akka.io._
import akka.io.Tcp._
import akka.util.ByteString

import nl.gideondk.sentinel._
import nl.gideondk.sentinel.SentinelResolver

class ServerCore[Cmd, Evt](port: Int, description: String, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                           resolver: SentinelResolver[Evt, Cmd], workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {

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
      val antenna = context.actorOf(Props(new Antenna(init, resolver)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
      val tcpHandler = context.actorOf(TcpPipelineHandler.props(init, connection, antenna).withDeploy(Deploy.local))

      antenna ! Management.RegisterTcpHandler(tcpHandler)
      connection ! Tcp.Register(tcpHandler)
  }
}

object SentinelServer {
  def apply[Evt, Cmd](serverPort: Int, resolver: SentinelResolver[Evt, Cmd], description: String = "Sentinel Server")(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 100L, highBytes: Long = 50 * 1024L, maxBufferSize: Long = 1000L * 1024L)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(Props(new ServerCore(serverPort, description, stages, resolver)(lowBytes, highBytes, maxBufferSize)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"), name = "sentinel-server-" + java.util.UUID.randomUUID.toString)
  }
}
