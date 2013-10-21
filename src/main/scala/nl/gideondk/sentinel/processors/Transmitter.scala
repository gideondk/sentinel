package nl.gideondk.sentinel.processors

import scala.collection.immutable.Queue
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

import akka.actor._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.pattern.ask
import akka.util.Timeout

import scalaz.contrib.std.scalaFuture.futureInstance

import scalaz.stream.Process
import scalaz.stream.Process._

import nl.gideondk.sentinel._
import CatchableFuture._

object Transmitter {
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

class Transmitter[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], streamChunkTimeout: Timeout = Timeout(5 seconds)) extends Actor with ActorLogging with Stash {
  import Transmitter._
  import TransmitterAction._
  import context.dispatcher

  var responseQueue = Queue.empty[Promise[HandleResult]]

  def processAction(data: Evt, action: TransmitterAction[Evt, Cmd]) = {
    val worker = self
    val future = action match {
      case x: Signal[Evt, Cmd] ⇒
        val promise = Promise[HandleResult]()
        responseQueue :+= promise

        for {
          response ← x.f(data) map (result ⇒ HandleAsyncResult(result))
        } yield {
          promise.success(response)
          worker ! DequeueResponse
        }

      case x: ProduceStream[Evt, Cmd] ⇒
        val promise = Promise[HandleResult]()
        responseQueue :+= promise

        for {
          response ← x.f(data) map (result ⇒ HandleStreamResult(result))
        } yield {
          promise.success(response)
          worker ! DequeueResponse
        }

      case x: ConsumeStream[Evt, Cmd] ⇒
        val promise = Promise[HandleResult]()
        responseQueue :+= promise

        val streamPromise = Promise[Process[Future, Evt]]()
        context.parent ! Registration.StreamReplyRegistration(streamPromise)

        for {
          source ← streamPromise.future
          response ← x.f(data)(source) map (result ⇒ HandleAsyncResult(result))
        } yield {
          promise.success(response)
          worker ! DequeueResponse
        }

      case x: ReactToStream[Evt, Cmd] ⇒
        val promise = Promise[HandleResult]()
        responseQueue :+= promise

        val streamPromise = Promise[Process[Future, Evt]]()
        context.parent ! Registration.StreamReplyRegistration(streamPromise)

        for {
          source ← streamPromise.future
          response ← x.f(data) map (result ⇒ HandleStreamResult(source through result))
        } yield {
          promise.success(response)
          worker ! DequeueResponse
        }
    }

    future.onFailure {
      case e ⇒
        log.error(e, e.getMessage)
        context.stop(self)
    }
  }

  def handleRequest: Receive = {
    case x: TransmitterActionAndData[Evt, Cmd] ⇒
      processAction(x.data, x.action)
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
    case x: HandleAsyncResult[Cmd] ⇒ context.parent ! Reply.Response(x.response)
    case x: HandleStreamResult[Cmd] ⇒
      val worker = self
      implicit val timeout = streamChunkTimeout
      val s = x.stream to actorResource((worker ? StartStreamHandling).map(x ⇒ worker))((a: ActorRef) ⇒ (a ? StreamProducerEnded).map(x ⇒ ()))((a: ActorRef) ⇒ Future { (c: Cmd) ⇒ (self ? StreamProducerChunk(c)).map(x ⇒ ()) })
      s.run
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
      context.parent ! Reply.StreamResponseChunk(c)
    case StreamProducerEnded ⇒
      sender ! StreamProducerChunkReceived
      context.become(handleRequestAndResponse)
      unstashAll()
    case _ ⇒ stash()
  }

  def receive = handleRequestAndResponse
}