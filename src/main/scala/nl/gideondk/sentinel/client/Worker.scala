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

import akka.pattern.pipe
import scala.concurrent.Promise

class SentinelClientWorker[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                                     workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging with Stash {
  import context.dispatcher
  import SentinelClientWorker._

  val tcp = akka.io.IO(Tcp)(context.system)

  /* Current open requests */
  val promises = Queue[Promise[Evt]]()

  override def preStart = {
    tcp ! Tcp.Connect(address)
  }

  def receive = {
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
      context.become(handleOutgoingStream(init, connection, o.stream), discardOld = false)

    case BackpressureBuffer.HighWatermarkReached ⇒
      context.become(handleResponses(init, connection) orElse {
        case BackpressureBuffer.LowWatermarkReached ⇒
          unstashAll()
          context.unbecome()
        case _: SentinelCommand ⇒ stash()
      }, discardOld = false)
  }

  def handleOutgoingStream(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef, stream: Enumerator[Cmd]): Receive = {
    case class StreamFinished()
    case class StreamChunk(c: Cmd)

      def iteratee: Iteratee[Cmd, Unit] = {
          def step(i: Input[Cmd]): Iteratee[Cmd, Unit] = i match {
            case Input.EOF ⇒
              Done(Unit, Input.EOF)
            case Input.Empty ⇒ Cont[Cmd, Unit](i ⇒ step(i))
            case Input.El(e) ⇒
              self ! StreamChunk(e)
              Cont[Cmd, Unit](i ⇒ step(i))
          }
        (Cont[Cmd, Unit](i ⇒ step(i)))
      }

    (stream |>>> iteratee).map(x ⇒ StreamFinished()).pipeTo(self)

    handleResponses(init, connection) orElse {
      case StreamChunk(x) ⇒
        connection ! init.Command(x)
      case x: StreamFinished ⇒
        unstashAll()
        context.unbecome()
      case scala.util.Failure(e: Throwable) ⇒
        log.error(e.getMessage)
        context.stop(self)
      case BackpressureBuffer.HighWatermarkReached ⇒
        context.become(handleResponses(init, connection) orElse {
          case BackpressureBuffer.LowWatermarkReached ⇒
            unstashAll()
            context.unbecome()
          case _: SentinelCommand | _: StreamChunk ⇒ stash()
        }, discardOld = false)
      case _: SentinelCommand ⇒ stash()
    }
  }
}

class WaitingSentinelClientWorker[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                                            workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends SentinelClientWorker(address, stages, workerDescription)(lowBytes, highBytes, maxBufferSize) {

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
      super.connected(init, connection)(BackpressureBuffer.HighWatermarkReached)
  }
}

private[sentinel] object SentinelClientWorker {
  /* Worker commands */
  case class ConnectToHost(address: InetSocketAddress)
}
