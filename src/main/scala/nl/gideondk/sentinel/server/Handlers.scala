package nl.gideondk.sentinel.server

import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

import akka.actor.{ Actor, ActorLogging, ActorRef, Stash, actorRef2Scala }
import akka.io.BackpressureBuffer
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }

class SentinelServerBasicAsyncHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], handler: Evt ⇒ Future[Cmd]) extends Actor with ActorLogging with Stash {
  import context.dispatcher

  case class HandleAsyncResult[B](recipient: ActorRef, response: B)
  case object DequeueResponse

  val responseQueue = mutable.Queue[Promise[HandleAsyncResult[Cmd]]]()

  def handleRequest: Receive = {
    case init.Event(data) ⇒ {
      val recipient = sender
      val serverWorker = self
      val promise = Promise[HandleAsyncResult[Cmd]]()
      responseQueue.enqueue(promise)

      val fut = for {
        response ← handler(data) map (result ⇒ HandleAsyncResult(recipient, result))
      } yield {
        promise.success(response)
        serverWorker ! DequeueResponse
      }

      fut.onFailure {
        // If the future failed, message sequence isn't certain; tear down line to let client recover.
        case e ⇒
          log.error(e, e.getMessage)
          context.stop(self)
      }
    }
  }

  def handleRequestAndResponse: Receive = handleRequest orElse {
    case x: HandleAsyncResult[Cmd] ⇒
      x.recipient ! init.Command(x.response)

    case DequeueResponse ⇒ {
        def dequeueAndSend: Unit = {

          if (!responseQueue.isEmpty && responseQueue.front.isCompleted) {
            // TODO: Should be handled a lot safer!
            responseQueue.dequeue.future.value match {
              case Some(Success(v)) ⇒
                self ! v
                dequeueAndSend
              case Some(Failure(e)) ⇒
                log.error(e, e.getMessage)
                context.stop(self)
            }
          }

        }
      dequeueAndSend
    }

    case BackpressureBuffer.HighWatermarkReached ⇒
      context.become(highWaterMark)
  }

  def highWaterMark: Receive = {
    case BackpressureBuffer.LowWatermarkReached ⇒
      unstashAll()
      context.become(handleRequestAndResponse)
    case _ ⇒
      stash()
  }

  def receive = handleRequestAndResponse
}
