package nl.gideondk.sentinel.processors

import scala.collection.immutable.Queue
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

import akka.actor._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.pattern.ask
import akka.util.Timeout

import scalaz._
import Scalaz._

import play.api.libs.iteratee._

import nl.gideondk.sentinel._

object Producer {
  trait HandleResult
  case class HandleAsyncResult[Cmd](response: Cmd) extends HandleResult
  case class HandleStreamResult[Cmd](stream: Enumerator[Cmd]) extends HandleResult

  trait StreamProducerMessage
  case class StreamProducerChunk[Cmd](c: Cmd) extends StreamProducerMessage

  case object StartStreamHandling extends StreamProducerMessage
  case object ReadyForStream extends StreamProducerMessage
  case object StreamProducerEnded extends StreamProducerMessage
  case object StreamProducerChunkReceived extends StreamProducerMessage

  case object DequeueResponse
}

class Producer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], streamChunkTimeout: Timeout = Timeout(5 seconds)) extends Actor with ActorLogging with Stash {
  import Producer._
  import ProducerAction._
  import context.dispatcher

  var responseQueue = Queue.empty[Promise[HandleResult]]

  def produceAsyncResult(data: Evt, f: Evt ⇒ Future[Cmd]) = {
    val worker = self
    val promise = Promise[HandleResult]()
    responseQueue :+= promise

    for {
      response ← f(data) map (result ⇒ HandleAsyncResult(result))
    } yield {
      promise.success(response)
      worker ! DequeueResponse
    }
  }

  def produceStreamResult(data: Evt, f: Evt ⇒ Future[Enumerator[Cmd]]) = {
    val worker = self
    val promise = Promise[HandleResult]()
    responseQueue :+= promise

    for {
      response ← f(data) map (result ⇒ HandleStreamResult(result))
    } yield {
      promise.success(response)
      worker ! DequeueResponse
    }
  }

  val initSignal = produceAsyncResult(_, _)
  val initStreamConsumer = produceAsyncResult(_, _)
  val initStreamProducer = produceStreamResult(_, _)

  def processAction(data: Evt, action: ProducerAction[Evt, Cmd]) = {
    val worker = self
    val future = action match {
      case x: Signal[Evt, Cmd]        ⇒ initSignal(data, x.f)

      case x: ProduceStream[Evt, Cmd] ⇒ initStreamProducer(data, x.f)

      case x: ConsumeStream[Evt, Cmd] ⇒
        val imcomingStreamPromise = Promise[Enumerator[Evt]]()
        context.parent ! Registration.StreamReplyRegistration(imcomingStreamPromise)
        imcomingStreamPromise.future flatMap ((s) ⇒ initStreamConsumer(data, x.f(_)(s)))
    }

    future.onFailure {
      case e ⇒
        log.error(e, e.getMessage)
        context.stop(self)
    }
  }

  def handleRequest: Receive = {
    case x: ProducerActionAndData[Evt, Cmd] ⇒
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

      (x.stream |>>> Iteratee.foldM(())((a, b) ⇒ (worker ? StreamProducerChunk(b)).map(x ⇒ ()))).flatMap(x ⇒ (worker ? StreamProducerEnded))

      context.become(handleRequestAndStreamResponse)

    case x: StreamProducerMessage ⇒
      log.error("Internal leakage in stream: received unexpected stream chunk")
      context.stop(self)
  }

  def handleRequestAndStreamResponse: Receive = handleRequest orElse {
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