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

class Answerer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt]) extends Actor with ActorLogging with Stash {
  import context.dispatcher

  trait HandleResult
  case class HandleAsyncResult(response: Cmd) extends HandleResult
  case class HandleStreamResult(response: Process[Future, Cmd]) extends HandleResult

  trait StreamChunk
  case class StreamData[Cmd](c: Cmd) extends StreamChunk
  case object StreamEnd extends StreamChunk
  case object StreamChunkReceived

  case object DequeueResponse

  var responseQueue = Queue.empty[Promise[HandleResult]]

  def handleRequest: Receive = {
    case x: Answer[Evt, Cmd] ⇒
      val serverWorker = self
      val promise = Promise[HandleResult]()
      responseQueue :+= promise

      val fut = for {
        response ← x.future map (result ⇒ HandleAsyncResult(result))
      } yield {
        promise.success(response)
        serverWorker ! DequeueResponse
      }

      fut.onFailure {
        case e ⇒
          log.error(e, e.getMessage)
          context.stop(self)
      }
    case x: ProduceStream[Evt, Cmd] ⇒
      val serverWorker = self
      val promise = Promise[HandleResult]()
      responseQueue :+= promise

      val fut = for {
        response ← x.futureProcess map (result ⇒ HandleStreamResult(result))
      } yield {
        promise.success(response)
        serverWorker ! DequeueResponse
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
    case x: HandleAsyncResult ⇒ context.parent ! Command.Reply(x.response)
    case x: HandleStreamResult ⇒
      x.stream
      context.become(handleRequestAndStreamResponse)
    case x: StreamChunk ⇒
      log.error("Internal leakage in stream: received stream unexpected stream chunk")
      context.stop(self)
  }

  def handleRequestAndStreamResponse: Receive = handleRequest orElse handleDequeue orElse {
    case StreamData(c) ⇒
      sender ! StreamChunkReceived
      context.parent ! Command.StreamReply(x.response)
    case StreamEnd ⇒
      sender ! StreamChunkReceived
      context.become(handleRequestAndResponse)
    case _ ⇒ stash()
  }

  def receive = handleRequestAndResponse
}