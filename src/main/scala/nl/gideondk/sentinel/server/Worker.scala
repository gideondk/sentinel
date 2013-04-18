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
                                                                 workerDescription: String = "Sentinel Client Worker", ackCount: Int, maxBufferSize: Long) extends Actor {

  import SentinelServerWorker._
  import context.dispatcher

  val log = Logging(context.system, this)

  /* IO actor (manages connections) */

  val responseQueue = mutable.Queue[Promise[HandleServerResponse[Cmd]]]()

  val ioWorker = context.actorOf(Props(new SentinelServerIOWorker(workerDescription + "-IO", ackCount, maxBufferSize)).withDispatcher("nl.gideondk.sentinel.sentinel-client-worker-dispatcher"))

  /* Request / reponse pipeline */
  val pipeline = PipelineFactory.buildWithSinkFunctions(pipelineCtx, stages)(cmd ⇒ ioWorker ! RawServerCommand(cmd), evt ⇒ self ! HandleServerEvent(evt))

  def receive = {
    case c: Connected ⇒
      ioWorker forward c

    case rw: RawServerEvent ⇒
      pipeline.injectEvent(rw.bs)

    case hsc: HandleServerResponse[Cmd] ⇒
      pipeline.injectCommand(hsc.response)

    case hs: HandleServerEvent[Evt] ⇒ {
      val serverWorker = self
      val promise = Promise[HandleServerResponse[Cmd]]()
      responseQueue.enqueue(promise)

      val fut = for {
        response ← handler(hs.event.get) map (result ⇒ HandleServerResponse(result)) // Unsafe get on try, but will tear down tcp worker in later stage
      } yield {
        promise.success(response)
        serverWorker ! DequeueResponse
      }

      fut.onFailure {
        // If the future failed, message sequence isn't certain; tear down line to let client recover.
        case e ⇒ ioWorker ! ErrorClosed(e.getMessage)
      }
    }

    case DequeueResponse ⇒ {
        def dequeueAndSend: Unit = {
          Try {
            if (responseQueue.front.isCompleted) {
              // TODO: Should be handled a lot safer!
              self ! responseQueue.dequeue.future.value.get.get
              dequeueAndSend
            }
          }
        }
      dequeueAndSend
    }
  }
}

private[sentinel] object SentinelServerWorker {
  /* Worker commands */
  case class ConnectToHost(address: InetSocketAddress)

  case class RawServerEvent(bs: ByteString)
  case class RawServerCommand(bs: Try[ByteString])

  case object DequeueResponse
  case class HandleServerEvent[A](event: Try[A])
  case class HandleServerResponse[B](response: B)

  /* Worker exceptions */
  trait WorkerConnectionException extends Exception
}

