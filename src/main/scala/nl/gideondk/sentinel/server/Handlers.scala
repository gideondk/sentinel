package nl.gideondk.sentinel.server

import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import akka.actor._
import akka.io.BackpressureBuffer
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import scalaz.stream._
import scalaz.stream.Process._
import nl.gideondk.sentinel._
import scala.util.Try
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import akka.io.{ BackpressureBuffer, PipelineContext, PipelineStage, Tcp }
import akka.io.Tcp.{ Bind, Bound, CommandFailed, Connected }
import akka.io.TcpPipelineHandler
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.io.TcpReadWriteAdapter
import akka.util.ByteString
import java.net.InetSocketAddress

// object Management {
// 	case class RegisterTcpHandler(h: ActorRef)

// 	case class RegisterReply[A](promise: Promise[A])
// 	case object ReplyRegistered
// }

// trait TxProcessors {

// }

// //object Antenna extends RxProcessors with TxProcessors { }

// //class SentinelServerBasicSyncHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], handler: Evt ⇒ Cmd) extends SentinelServerHandler(init, (x: Evt) ⇒ SentinelServerHandler.Response(handler(x)))
// //
// //class SentinelServerBasicAsyncHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], handler: Evt ⇒ Future[Cmd]) extends SentinelServerHandler(init, (x: Evt) ⇒ SentinelServerHandler.AsyncResponse(handler(x)))

//class ClientAntennaManager[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], decider: Action.Decider[Evt, Cmd]) extends Actor with ActorLogging with Stash {
//  val tcp = akka.io.IO(Tcp)(context.system)
//  var receiverQueue = Queue.empty[ActorRef]
//
//  override def preStart = tcp ! Tcp.Connect(address)
//
//  def connected(antenna: ActorRef): Receive = {
//    case x: Action ⇒ antenna forward x
//  }
//
//  def disconnected: Receive = {
//    case Connected(remoteAddr, localAddr) ⇒
//      val init = TcpPipelineHandler.withLogger(log,
//        stages >>
//          new TcpReadWriteAdapter)
//
//      val handler = context.actorOf(TcpPipelineHandler.props(init, sender, self).withDeploy(Deploy.local))
//      context watch handler
//
//      sender ! Register(handler)
//      val antenna = context.actorOf(Props(new Antenna(init, decider)))
//
//      unstashAll()
//      context.become(connected(antenna))
//
//    case CommandFailed(cmd: akka.io.Tcp.Command) ⇒
//      context.stop(self) // Bit harsh at the moment, but should trigger reconnect and probably do better next time...
//
//    //    case x: SentinelCommand[_] ⇒
//    //      x.promise.failure(NoConnectionAvailable("Client has not yet been connected to a endpoint"))
//
//    case _ ⇒ stash()
//  }
//}
//
//class ServerAntennaManager[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], decider: Action.Decider[Evt, Cmd]) extends Actor with ActorLogging with Stash {
//  val tcp = akka.io.IO(Tcp)(context.system)
//  var receiverQueue = Queue.empty[ActorRef]
//
//  override def preStart = tcp ! Tcp.Connect(address)
//
//  def connected(antenna: ActorRef): Receive = {
//    case x: Command.Ask[Cmd, Evt] ⇒ antenna forward x
//  }
//
//  def disconnected: Receive = {
//    case Connected(remoteAddr, localAddr) ⇒
//      val init = TcpPipelineHandler.withLogger(log,
//        stages >>
//          new TcpReadWriteAdapter)
//
//      val handler = context.actorOf(TcpPipelineHandler.props(init, sender, self).withDeploy(Deploy.local))
//      context watch handler
//
//      sender ! Register(handler)
//      val antenna = context.actorOf(Props(new Antenna(init, decider)))
//
//      unstashAll()
//      context.become(connected(antenna))
//
//    case CommandFailed(cmd: akka.io.Tcp.Command) ⇒
//      context.stop(self) // Bit harsh at the moment, but should trigger reconnect and probably do better next time...
//
//    //    case x: SentinelCommand[_] ⇒
//    //      x.promise.failure(NoConnectionAvailable("Client has not yet been connected to a endpoint"))
//
//    case _ ⇒ stash()
//  }
//}

class ServerCore[Cmd, Evt](port: Int, description: String, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                           decider: Action.Decider[Evt, Cmd], workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {

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
      val antenna = context.actorOf(Props(new Antenna(init, decider)))
      val tcpHandler = context.actorOf(TcpPipelineHandler.props(init, connection, antenna).withDeploy(Deploy.local))

      antenna ! Management.RegisterTcpHandler(tcpHandler)
      connection ! Tcp.Register(tcpHandler)
  }

  //  def receive = {
  //    case x: Client.ConnectToServer ⇒
  //      if (!addresses.map(_._1).contains(x)) {
  //        val router = routerProto(x.addr)
  //        context.watch(router)
  //        addresses :+ x.addr -> router
  //        coreRouter = Some(context.system.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = addresses.map(_._2).flatten))))
  //      }
  //
  //    case Terminated(actor) ⇒
  //      /* If router died, restart after a period of time */
  //      val terminatedRouter = addresses.find(_._2 == actor)
  //      terminatedRouter match {
  //        case Some(r) ⇒
  //          addresses = addresses diff addresses.find(_._2 == actor).toList
  //          coreRouter = Some(context.system.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = addresses.map(_._2).flatten))))
  //          log.debug("Router for: " + r._1 + " died, restarting in: " + reconnectDuration.toString())
  //          context.system.scheduler.scheduleOnce(reconnectDuration, self, Client.ConnectToServer(r._1))
  //        case None ⇒
  //      }
  //
  //    case x: Command.Ask[_, _] ⇒
  //      coreRouter match {
  //        case Some(r) ⇒ r forward x
  //        case None ⇒ x.pp.failure(new Exception("No connection(s) available"))
  //      }
  //
  //    case _ ⇒
  //  }
}