package nl.gideondk.sentinel.client

import java.net.InetSocketAddress

import scala.concurrent._
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

import akka.actor._
import akka.io._
import akka.io.Tcp._
import akka.routing._

import akka.util.ByteString

import scala.collection.immutable.Queue
import scalaz.stream._

import nl.gideondk.sentinel._
import nl.gideondk.sentinel.Registration._

trait Client[Cmd, Evt] {
  import Registration._

  def actor: ActorRef

  def <~<(command: Cmd)(implicit context: ExecutionContext): Task[Evt] = ask(command)

  def ask(command: Cmd)(implicit context: ExecutionContext): Task[Evt] = Task {
    val promise = Promise[Evt]()
    actor ! Command.Ask(command, ReplyRegistration(promise))
    promise.future
  }

  def askStream(command: Cmd)(implicit context: ExecutionContext): Task[Process[Future, Evt]] = Task {
    val promise = Promise[Process[Future, Evt]]()
    actor ! Command.AskStream(command, StreamReplyRegistration(promise))
    promise.future
  }

  def sendStream(command: Cmd, source: Process[Future, Evt]): Task[Evt] = Task {
    val promise = Promise[Evt]()
    actor ! Command.SendStream(command, source, ReplyRegistration(promise))
    promise.future
  }

  // def conversate(command: Cmd): Task[Channel[Future, Cmd, Evt]] = Task {
  //   val promise = Promise[Process[Future, Evt]]()
  //   actor ! Command.Conversate(command, source, StreamReplyRegistration(terminator, includeTerminator, promise))
  //   promise.future
  // }
}

object Client {
  case class ConnectToServer(addr: InetSocketAddress)

  def defaultResolver[Cmd, Evt] = new SentinelResolver[Evt, Cmd] {
    def process = {
      case _ ⇒ ConsumerAction.Consume
    }
  }

  def apply[Cmd, Evt](serverHost: String, serverPort: Int, routerConfig: RouterConfig,
                      description: String = "Sentinel Client", workerReconnectTime: FiniteDuration = 2 seconds, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], Resolver: SentinelResolver[Evt, Cmd] = Client.defaultResolver[Cmd, Evt], lowBytes: Long = 100L, highBytes: Long = 5000L, maxBufferSize: Long = 20000L)(implicit system: ActorSystem) = {
    val core = system.actorOf(Props(new ClientCore[Cmd, Evt](routerConfig, description, workerReconnectTime, stages, Resolver)(lowBytes, highBytes, maxBufferSize)))
    core ! Client.ConnectToServer(new InetSocketAddress(serverHost, serverPort))
    new Client[Cmd, Evt] {
      val actor = core
    }
  }
}

class ClientAntennaManager[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], Resolver: SentinelResolver[Evt, Cmd]) extends Actor with ActorLogging with Stash {
  val tcp = akka.io.IO(Tcp)(context.system)
  var receiverQueue = Queue.empty[ActorRef]

  override def preStart = tcp ! Tcp.Connect(address)

  def connected(antenna: ActorRef): Receive = {
    case x: Command.Ask[Cmd, Evt] ⇒
      antenna forward x
  }

  def disconnected: Receive = {
    case Connected(remoteAddr, localAddr) ⇒
      val init = TcpPipelineHandler.withLogger(log,
        stages >>
          new TcpReadWriteAdapter >>
          new BackpressureBuffer(100, 50 * 1024L, 1000 * 1024L))

      val antenna = context.actorOf(Props(new Antenna(init, Resolver)))
      val handler = context.actorOf(TcpPipelineHandler.props(init, sender, antenna).withDeploy(Deploy.local))
      context watch handler

      sender ! Register(handler)
      antenna ! nl.gideondk.sentinel.Management.RegisterTcpHandler(handler)

      unstashAll()
      context.become(connected(antenna))

    case CommandFailed(cmd: akka.io.Tcp.Command) ⇒
      context.stop(self) // Bit harsh at the moment, but should trigger reconnect and probably do better next time...

    //    case x: SentinelCommand[_] ⇒
    //      x.promise.failure(NoConnectionAvailable("Client has not yet been connected to a endpoint"))

    case _ ⇒ stash()
  }

  def receive = disconnected
}

class ClientCore[Cmd, Evt](routerConfig: RouterConfig, description: String, reconnectDuration: FiniteDuration,
                           stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], Resolver: SentinelResolver[Evt, Cmd], workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {

  import context.dispatcher

  var addresses = List.empty[Tuple2[InetSocketAddress, Option[ActorRef]]]

  private case object InitializeRouter

  private case class ReconnectRouter(address: InetSocketAddress)

  var coreRouter: Option[ActorRef] = None

  def antennaManagerProto(address: InetSocketAddress) =
    new ClientAntennaManager(address, stages, Resolver)

  def routerProto(address: InetSocketAddress) =
    context.system.actorOf(Props(antennaManagerProto(address)).withRouter(routerConfig).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))

  override def preStart = {
    self ! InitializeRouter
  }

  def receive = {
    case x: Client.ConnectToServer ⇒
      if (!addresses.map(_._1).contains(x)) {
        val router = routerProto(x.addr)
        context.watch(router)
        addresses = addresses ++ List(x.addr -> Some(router))
        coreRouter = Some(context.system.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = addresses.map(_._2).flatten))))
      }

    case Terminated(actor) ⇒
      /* If router died, restart after a period of time */
      val terminatedRouter = addresses.find(_._2 == actor)
      terminatedRouter match {
        case Some(r) ⇒
          addresses = addresses diff addresses.find(_._2 == actor).toList
          coreRouter = Some(context.system.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = addresses.map(_._2).flatten))))
          log.debug("Router for: " + r._1 + " died, restarting in: " + reconnectDuration.toString())
          context.system.scheduler.scheduleOnce(reconnectDuration, self, Client.ConnectToServer(r._1))
        case None ⇒
      }

    case x: Command.Ask[Cmd, Evt] ⇒
      coreRouter match {
        case Some(r) ⇒
          r forward x
        case None ⇒ x.registration.promise.failure(new Exception("No connection(s) available"))
      }

    case _ ⇒
  }
}