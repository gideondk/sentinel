package nl.gideondk.sentinel.client

import java.net.InetSocketAddress

import scala.collection.mutable.Queue

import akka.actor.{ Actor, ActorLogging, ActorRef, Deploy, Stash, Terminated, actorRef2Scala }
import akka.io.{ BackpressureBuffer, PipelineContext, PipelineStage, Tcp }
import akka.io.Tcp.{ Command, CommandFailed, Connected, Register }
import akka.io.TcpPipelineHandler
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.io.TcpReadWriteAdapter
import akka.util.ByteString
import nl.gideondk.sentinel._

import play.api.libs.iteratee._

import scala.concurrent.Promise

class SentinelClientWorker[Cmd, Evt](stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                                     workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging with Stash {
  import context.dispatcher
  import SentinelClientWorker._

  val tcp = akka.io.IO(Tcp)(context.system)

  /* Current open requests */
  val promises = Queue[Promise[Evt]]()

  def receive = {
    case h: ConnectToHost ⇒
      tcp ! Tcp.Connect(h.address)

    case Connected(remoteAddr, localAddr) ⇒
      val init =
        TcpPipelineHandler.withLogger(log,
          stages >>
            new TcpReadWriteAdapter >>
            new BackpressureBuffer(lowBytes, highBytes, maxBufferSize))

      val handler = context.actorOf(TcpPipelineHandler.props(init, sender, self).withDeploy(Deploy.local).withDeploy(Deploy.local))
      context watch handler

      sender ! Register(handler)
      unstashAll()
      context.become(connected(init, handler))

    case CommandFailed(cmd: Command) ⇒
      context.stop(self) // Bit harsh at the moment, but should trigger reconnect and probably do better next time...

    case _ ⇒ stash()
  }

  def handleResponses(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = {
    case init.Event(data) ⇒
      val pr = promises.dequeue
      pr.success(data)

    case Terminated(`connection`) ⇒
      promises.foreach(_.failure(new Exception("TCP Actor disconnected")))
      context.stop(self)
  }

  def connected(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = handleResponses(init, connection) orElse {
    case o: Operation[Cmd, Evt] ⇒
      promises.enqueue(o.promise)
      connection ! init.Command(o.command)

    case o: StreamedOperation[Cmd, Evt] ⇒
      promises.enqueue(o.promise)
      o.stream |>>> Iteratee.foreach(x ⇒ connection ! init.Command(x))

    case BackpressureBuffer.HighWatermarkReached ⇒
      context.become(highWaterMark(init, connection))
  }

  def highWaterMark(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = handleResponses(init, connection) orElse {
    case BackpressureBuffer.LowWatermarkReached ⇒
      unstashAll()
      context.become(connected(init, connection))
    case _: Operation[Cmd, Evt] ⇒ stash()
  }
}

class WaitingSentinelClientWorker[Cmd, Evt](stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                                            workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends SentinelClientWorker(stages, workerDescription)(lowBytes, highBytes, maxBufferSize) {

  import context.dispatcher

  var requestRunning = false
  val requests = Queue[SentinelCommand]()

  override def handleResponses(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = {
    case init.Event(data) ⇒
      val pr = promises.dequeue
      pr.success(data)
      requestRunning = false
      if (requests.length > 0) connected(init, connection)(requests.dequeue)

    case Terminated(`connection`) ⇒
      promises.foreach(_.failure(new Exception("TCP Actor disconnected")))
      context.stop(self)
  }

  override def connected(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = handleResponses(init, connection) orElse {
    case o: Operation[Cmd, Evt] ⇒
      requestRunning match {
        case false ⇒
          super.connected(init, connection)(o)
          requestRunning = true
        case true ⇒ requests.enqueue(o)
      }

    case o: StreamedOperation[Cmd, Evt] ⇒
      requestRunning match {
        case false ⇒
          super.connected(init, connection)(o)
          requestRunning = true
        case true ⇒ requests.enqueue(o)
      }

    case BackpressureBuffer.HighWatermarkReached ⇒
      context.become(highWaterMark(init, connection))
  }
}

private[sentinel] object SentinelClientWorker {
  /* Worker commands */
  case class ConnectToHost(address: InetSocketAddress)
}
