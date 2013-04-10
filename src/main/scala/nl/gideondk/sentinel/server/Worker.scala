package nl.gideondk.sentinel.server

import nl.gideondk.sentinel._
import scala.collection.mutable.Queue
import akka.actor.IO.IterateeRef
import akka.io._
import akka.io.Tcp._
import akka.actor._
import akka.routing._
import akka.util.ByteString
import akka.event.Logging
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import akka.actor._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Promise

import scala.util.Try
import scala.collection._

class SentinelServerWorker[Cmd, Evt, Context <: PipelineContext](pipelineCtx: ⇒ Context, stages: PipelineStage[Context, Cmd, ByteString, Evt, ByteString], handler: Evt ⇒ Future[Cmd],
                                                                 workerDescription: String = "Sentinel Client Worker", writeAck: Boolean = true) extends Actor {

  import SentinelServerWorker._
  import context.dispatcher

  /* IO actor (manages connections) */
  val ioActor = context.actorOf(Props(new SentinelServerIOWorker(workerDescription + "-IO", writeAck)).withDispatcher("nl.gideondk.sentinel.sentinel-client-worker-dispatcher"))

  val responseQueueHolder = ResponseQueueHolder(mutable.Queue[Promise[HandleServerResponse[Cmd]]]())

  /* Request / reponse pipeline */
  def pipelineProto(tcpActor: ActorRef) = PipelineFactory.buildWithSinkFunctions(pipelineCtx, stages)(cmd ⇒ ioActor ! RawServerCommand(tcpActor, cmd), evt ⇒ self ! HandleServerEvent(tcpActor, evt))
  val pipelineHolder = PipelineInjectorHolder(pipelineProto)

  def receive = {
    case c: Connected ⇒
      ioActor forward c

    case rw: RawServerEvent ⇒
      pipelineHolder.get(rw.tcpWorker).injectEvent(rw.bs)

    case hsc: HandleServerResponse[Cmd] ⇒
      pipelineHolder.get(hsc.tcpWorker).injectCommand(hsc.response)

    case hs: HandleServerEvent[Evt] ⇒ {
      val serverWorker = self
      val promise = Promise[HandleServerResponse[Cmd]]()
      responseQueueHolder.get(hs.tcpWorker).enqueue(promise)

      val fut = for {
        response ← handler(hs.event.get) map (result ⇒ HandleServerResponse(hs.tcpWorker, result)) // Unsafe get on try, but will tear down tcp worker in later stage
      } yield {
        promise.success(response)
        serverWorker ! DequeueResponse(hs.tcpWorker)
      }

      fut.onFailure {
        // If the future failed, message sequence isn't certain; tear down line to let client recover.
        case e ⇒ hs.tcpWorker ! ErrorClosed(e.getMessage)
      }
    }

    case dr: DequeueResponse ⇒ {
      val tcpWorker = dr.tcpWorker
      val queue = responseQueueHolder.get(tcpWorker)
        def dequeueAndSend: Unit = {
          Try {
            if (queue.front.isCompleted) {
              // TODO: Should be handled a lot safer!
              self ! queue.dequeue.future.value.get.get
              dequeueAndSend
            }
          }
        }
      dequeueAndSend
    }
  }
}

private[sentinel] object SentinelServerWorker {
  class PipelineInjectorHolder[Cmd, Evt](refFactory: ActorRef ⇒ PipelineInjector[Cmd, Evt], underlying: mutable.Map[ActorRef, PipelineInjector[Cmd, Evt]] = mutable.Map.empty[ActorRef, PipelineInjector[Cmd, Evt]]) {
    def get(key: ActorRef) = underlying.getOrElseUpdate(key, refFactory(key))
  }

  object PipelineInjectorHolder {
    def apply[Cmd, Evt](refFactory: ActorRef ⇒ PipelineInjector[Cmd, Evt]): PipelineInjectorHolder[Cmd, Evt] = new PipelineInjectorHolder(refFactory)
  }

  class ResponseQueueHolder[Cmd](refFactory: ⇒ mutable.Queue[Promise[HandleServerResponse[Cmd]]], underlying: mutable.Map[ActorRef, mutable.Queue[Promise[HandleServerResponse[Cmd]]]] = mutable.Map.empty[ActorRef, mutable.Queue[Promise[HandleServerResponse[Cmd]]]]) {
    def get(key: ActorRef) = underlying.getOrElseUpdate(key, refFactory)
  }

  object ResponseQueueHolder {
    def apply[Cmd](refFactory: ⇒ mutable.Queue[Promise[HandleServerResponse[Cmd]]]): ResponseQueueHolder[Cmd] = new ResponseQueueHolder(refFactory)
  }

  /* Worker commands */
  case class ConnectToHost(address: InetSocketAddress)

  case class RawServerEvent(tcpWorker: ActorRef, bs: ByteString)
  case class RawServerCommand(tcpWorker: ActorRef, bs: Try[ByteString])

  case class DequeueResponse(tcpWorker: ActorRef)
  case class HandleServerEvent[A](tcpWorker: ActorRef, event: Try[A])
  case class HandleServerResponse[B](tcpWorker: ActorRef, response: B)

  /* Worker exceptions */
  trait WorkerConnectionException extends Exception
}

