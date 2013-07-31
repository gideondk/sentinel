package nl.gideondk.sentinel.client

import java.net.InetSocketAddress

import scala.collection.mutable.Queue

import akka.actor._
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

import scala.util.{ Success, Failure }

object SentinelClientWorker {
  case class ConnectToHost(address: InetSocketAddress)
  case object TcpActorDisconnected
  case object UpstreamFinished
}

class SentinelClientWorker[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                                     workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging with Stash {
  import context.dispatcher
  import SentinelClientWorker._

  val tcp = akka.io.IO(Tcp)(context.system)
  val receiverQueue = Queue[ActorRef]()

  override def preStart = tcp ! Tcp.Connect(address)

  def disconnected: Receive = {
    case Connected(remoteAddr, localAddr) ⇒
      val init = TcpPipelineHandler.withLogger(log,
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

  def connected(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = {
    val operationHandler = context.actorOf(Props(new OperationHandler(init, connection)))
    val upstreamHandler = context.actorOf(Props(new UpstreamHandler(init, connection)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))

    context watch operationHandler
    context watch upstreamHandler

      def handleResponses: Receive = {
        case x: init.Event ⇒
          receiverQueue.dequeue.forward(x)
      }

      def handleHighWaterMark: Receive = {
        case BackpressureBuffer.HighWatermarkReached ⇒
          upstreamHandler ! BackpressureBuffer.HighWatermarkReached
          context.become(handleResponses orElse {
            case BackpressureBuffer.LowWatermarkReached ⇒
              upstreamHandler ! BackpressureBuffer.LowWatermarkReached
              unstashAll()
              context.unbecome()
            case _: SentinelCommand ⇒ stash()
          }, discardOld = false)
      }

      /* Upstream handler, stashes new requests until up stream is finished */
      def handleUpstream: Receive = handleResponses orElse handleHighWaterMark orElse {
        case UpstreamFinished ⇒
          unstashAll()
          context.unbecome()
        case _ ⇒ stash()
      }

      def default: Receive = handleResponses orElse handleHighWaterMark orElse {
        case o: Operation[Cmd, Evt] ⇒
          receiverQueue enqueue operationHandler
          operationHandler forward o

        case so: StreamedOperation[Cmd, Evt] ⇒
          context.become(handleUpstream, discardOld = false)
          receiverQueue enqueue upstreamHandler
          upstreamHandler forward so

        case Terminated(`connection`) ⇒
          log.error(workerDescription + " has been terminated due to a terminated TCP worker")
          context.stop(self)

        case x: Terminated ⇒
          log.error(workerDescription + " has been terminated due to a internal error")
          context.stop(self)
      }

    default
  }

  def receive = disconnected
}

private class OperationHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef) extends Actor with ActorLogging {
  import SentinelClientWorker._

  context watch connection

  val promises = Queue[Promise[Evt]]()

  override def postStop = {
    promises.foreach(_.failure(new Exception("Actor quit unexpectedly")))
  }

  def receive: Receive = {
    case init.Event(data) ⇒
      val pr = promises.dequeue
      pr.success(data)

    case o: Operation[Cmd, Evt] ⇒
      promises.enqueue(o.promise)
      connection ! init.Command(o.command)
  }
}

private class UpstreamHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef) extends Actor with ActorLogging with Stash {
  import SentinelClientWorker._
  import context.dispatcher

  context watch connection

  val promises = Queue[Promise[Evt]]()

  override def postStop = {
    promises.foreach(_.failure(new Exception("Actor quit unexpectedly")))
  }

  def handleResponses: Receive = {
    case init.Event(data) ⇒
      val pr = promises.dequeue
      pr.success(data)
  }

  def receive: Receive = handleResponses orElse {
    case o: StreamedOperation[Cmd, Evt] ⇒
      promises.enqueue(o.promise)
      context.become(handleOutgoingStream(o.stream), discardOld = false)
  }

  def handleOutgoingStream(stream: Enumerator[Cmd]): Receive = {
    case object StreamFinished
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

    (stream |>>> iteratee) onComplete {
      case Success(result)  ⇒ self ! StreamFinished
      case Failure(failure) ⇒ self ! failure
    }

    handleResponses orElse {
      case StreamChunk(x) ⇒
        connection ! init.Command(x)

      case StreamFinished ⇒
        context.parent ! UpstreamFinished
        unstashAll()
        context.unbecome()

      case scala.util.Failure(e: Throwable) ⇒
        log.error(e.getMessage)
        context.stop(self)

      case BackpressureBuffer.HighWatermarkReached ⇒
        context.become(handleResponses orElse {
          case BackpressureBuffer.LowWatermarkReached ⇒
            unstashAll()
            context.unbecome()
          case _: SentinelCommand | _: StreamChunk | StreamFinished ⇒ stash()
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

  override def connected(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = {
    val r: Receive = {
      case x: init.Event ⇒
        receiverQueue.dequeue.forward(x)
        requestRunning = false
        if (requests.length > 0) connected(init, connection)(requests.dequeue)

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
    }
    r orElse super.connected(init, connection)
  }
}
