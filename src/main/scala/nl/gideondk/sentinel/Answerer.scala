package nl.gideondk.sentinel

import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import akka.actor._
import akka.io.BackpressureBuffer
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import scalaz.stream._
import scalaz.stream.Process._
import scala.util.Try
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import nl.gideondk.sentinel._
import scala.concurrent.ExecutionContext
import akka.dispatch._
import scalaz._
import Scalaz._
import com.typesafe.config.Config
import akka.actor.ActorSystem.Settings

import scala.concurrent.Future
import scalaz.contrib.std.scalaFuture._
import nl.gideondk.sentinel.CatchableFuture._

import Action._

object Answerer {
  def answererSink[O](acquire: Future[ActorRef])(release: ActorRef ⇒ Future[Unit])(step: ActorRef ⇒ Future[O])(implicit context: ExecutionContext): Process[Future, O] = {
      def go(step: Future[O], onExit: Process[Future, O]): Process[Future, O] =
        await[Future, O, O](step)(o ⇒ emit(o) ++ go(step, onExit), onExit, onExit)

    await(acquire)(r ⇒ {
      val onExit = eval(release(r)).drain
      go(step(r), onExit)
    }, halt, halt)
  }

  trait HandleResult
  case class HandleAsyncResult[Cmd](response: Cmd) extends HandleResult
  case class HandleStreamResult[Cmd](stream: Process[Future, Cmd]) extends HandleResult

  trait StreamProducerMessage
  case class StreamProducerChunk[Cmd](c: Cmd) extends StreamProducerMessage

  case object StartStreamHandling extends StreamProducerMessage
  case object ReadyForStream extends StreamProducerMessage
  case object StreamProducerEnded extends StreamProducerMessage
  case object StreamProducerChunkReceived extends StreamProducerMessage

  case object DequeueResponse
}

class Answerer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], streamChunkTimeout: Timeout = Timeout(5 seconds)) extends Actor with ActorLogging with Stash {
  import Answerer._
  import context.dispatcher

  var responseQueue = Queue.empty[Promise[HandleResult]]

  def handleRequest: Receive = {
    case x: Answer[Evt, Cmd] ⇒
      val me = self
      val promise = Promise[HandleResult]()
      responseQueue :+= promise

      val fut = for {
        response ← x.future map (result ⇒ HandleAsyncResult(result))
      } yield {
        promise.success(response)
        me ! DequeueResponse
      }

      fut.onFailure {
        case e ⇒
          log.error(e, e.getMessage)
          context.stop(self)
      }
    case x: ProduceStream[Evt, Cmd] ⇒
      val me = self
      val promise = Promise[HandleResult]()
      responseQueue :+= promise

      val fut = for {
        response ← x.futureProcess map (result ⇒ HandleStreamResult(result))
      } yield {
        promise.success(response)
        me ! DequeueResponse
      }

      fut.onFailure {
        case e ⇒
          log.error(e, e.getMessage)
          context.stop(self)
      }
  }

  def handleDequeue: Receive = {
    case DequeueResponse ⇒ {
        def dequeueAndSend: Unit = {
          if (!responseQueue.isEmpty && responseQueue.front.isCompleted) {
            // TODO: Should be handled a lot safer!
            val promise = responseQueue.head
            responseQueue = responseQueue.tail
            promise.future.value match {
              case Some(Success(v)) ⇒
                self ! v
                dequeueAndSend
              case Some(Failure(e)) ⇒ // Would normally not occur...
                log.error(e, e.getMessage)
                context.stop(self)
            }
          }

        }
      dequeueAndSend
    }
  }

  def handleRequestAndResponse: Receive = handleRequest orElse handleDequeue orElse {
    case x: HandleAsyncResult[Cmd] ⇒ context.parent ! Command.Reply(x.response)
    case x: HandleStreamResult[Cmd] ⇒
      val worker = self
      implicit val timeout = streamChunkTimeout
      x.stream to answererSink((worker ? StartStreamHandling).map(x ⇒ worker))((a: ActorRef) ⇒ (a ? StreamProducerEnded).mapTo[Unit])((a: ActorRef) ⇒ Future { (c: Cmd) ⇒ (self ? StreamProducerChunk(c)).mapTo[Unit] })
      context.become(handleRequestAndStreamResponse)
    case x: StreamProducerMessage ⇒
      log.error("Internal leakage in stream: received stream unexpected stream chunk")
      context.stop(self)
  }

  def handleRequestAndStreamResponse: Receive = handleRequest orElse handleDequeue orElse {
    case StartStreamHandling ⇒
      sender ! ReadyForStream
    case StreamProducerChunk(c) ⇒
      sender ! StreamProducerChunkReceived
      context.parent ! Command.StreamReply(c)
    case StreamProducerEnded ⇒
      sender ! StreamProducerChunkReceived
      context.become(handleRequestAndResponse)
    case _ ⇒ stash()
  }

  def receive = handleRequestAndResponse
}