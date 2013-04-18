package nl.gideondk.sentinel.client

import scala.collection.mutable.Queue

import akka.actor._
import akka.event.Logging

import akka.io._
import akka.util.ByteString

import java.net.InetSocketAddress

import nl.gideondk.sentinel._

class SentinelClientWorker[Cmd, Evt, Context <: PipelineContext](pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString],
                                                                 workerDescription: String = "Sentinel Client Worker", toAckCount: Int, maxBufferSize: Long) extends Actor {
  import context.dispatcher
  import SentinelClientWorker._

  val log = Logging(context.system, this)

  /* Current open requests */
  val requests = Queue[Operation[Cmd, Evt]]()

  /* IO actor (manages connections) */
  val ioActor = context.actorOf(Props(new SentinelClientIOWorker(workerDescription + "-IO", toAckCount, maxBufferSize)).withDispatcher("nl.gideondk.sentinel.sentinel-client-worker-dispatcher"))

  /* Request / reponse pipeline */
  val pipeline = PipelineFactory.buildWithSinkFunctions(pipelineCtx, stages)(cmd ⇒ ioActor ! RawCommand(cmd.get), evt ⇒ self ! HandleEvent(evt.get))

  def receive = {
    case h: ConnectToHost ⇒
      ioActor forward h

    case he: HandleEvent[Evt] ⇒
      val op = requests.dequeue
      op.promise.success(he.e)

    case e: RawEvent ⇒
      pipeline.injectEvent(e.bs)

    case o: Operation[Cmd, Evt] ⇒
      requests.enqueue(o)
      pipeline.injectCommand(o.command)
  }
}

private[sentinel] object SentinelClientWorker {
  /* Worker commands */
  case class ConnectToHost(address: InetSocketAddress)

  case class RawEvent(bs: ByteString)
  case class RawCommand(bs: ByteString)

  case class HandleEvent[A](e: A)

  /* Worker exceptions */
  trait WorkerConnectionException extends Exception

  case class PeerDisconnectedException extends WorkerConnectionException
  case class DisconnectException extends WorkerConnectionException
  case class DiconnectExceptionWithCause(c: String) extends WorkerConnectionException
}
