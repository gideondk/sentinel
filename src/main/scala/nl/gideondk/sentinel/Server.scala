package nl.gideondk.sentinel

import java.net.InetSocketAddress

import akka.actor._
import akka.io._
import akka.io.Tcp._
import akka.util.ByteString

import nl.gideondk.sentinel._
import nl.gideondk.sentinel.SentinelResolver
import scala.concurrent.{ Future, Promise, ExecutionContext }
import play.api.libs.iteratee.Enumerator

trait Server[Cmd, Evt] {
  def actor: ActorRef

  def ?*(command: Cmd)(implicit context: ExecutionContext): Task[List[Evt]] = askMany(command)

  def askMany(command: Cmd)(implicit context: ExecutionContext): Task[List[Evt]] = Task {
    val promise = Promise[List[Evt]]()
    actor ! ServerCommand.AskAll(command, promise)
    promise.future
  }
}

class ServerCore[Cmd, Evt](port: Int, description: String, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                           resolver: SentinelResolver[Evt, Cmd], workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {

  import context.dispatcher

  def wrapAtenna(a: ActorRef) = new Client[Cmd, Evt] {
    val actor = a
  }

  val tcp = akka.io.IO(Tcp)(context.system)
  val address = new InetSocketAddress(port)

  var connections = List[ActorRef]()

  override def preStart = {
    tcp ! Bind(self, address)
  }

  def receiveCommands: Receive = {
    case x: ServerCommand.AskAll[Cmd, Evt] ⇒
      val futures = Task.sequence(connections.map(wrapAtenna).map(_ ? x.payload)).start.flatMap {
        case scala.util.Success(s) ⇒ Future.successful(s)
        case scala.util.Failure(e) ⇒ Future.failed(e)
      }
      x.promise.completeWith(futures)
  }

  def receive = receiveCommands orElse {
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
      connections :+= antenna

      val tcpHandler = context.actorOf(TcpPipelineHandler.props(init, connection, antenna).withDeploy(Deploy.local))

      antenna ! Management.RegisterTcpHandler(tcpHandler)
      connection ! Tcp.Register(tcpHandler)
  }
}

object SentinelServer {
  def apply[Evt, Cmd](serverPort: Int, resolver: SentinelResolver[Evt, Cmd], description: String = "Sentinel Server")(stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 100L, highBytes: Long = 50 * 1024L, maxBufferSize: Long = 1000L * 1024L)(implicit system: ActorSystem) = {
    new Server[Evt, Cmd] {
      val actor = system.actorOf(Props(new ServerCore(serverPort, description, stages, resolver)(lowBytes, highBytes, maxBufferSize)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"), name = "sentinel-server-" + java.util.UUID.randomUUID.toString)
    }
  }
}
