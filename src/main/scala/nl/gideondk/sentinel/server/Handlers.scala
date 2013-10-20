package nl.gideondk.sentinel.server

import java.net.InetSocketAddress

import akka.actor._
import akka.io._
import akka.io.Tcp._
import akka.util.ByteString

import nl.gideondk.sentinel._

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
      val antenna = context.actorOf(Props(new Antenna(init, resolver)))
      val tcpHandler = context.actorOf(TcpPipelineHandler.props(init, connection, antenna).withDeploy(Deploy.local))

      antenna ! Management.RegisterTcpHandler(tcpHandler)
      connection ! Tcp.Register(tcpHandler)
  }
}