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

import scala.concurrent.Future
import scalaz.contrib.std.scalaFuture._

trait Action

object Action {
  class EmptyStreamResultException extends Exception

  case object Consume extends Action
  case object Ignore extends Action

  trait Reaction[Evt, Cmd] extends Action

  trait StreamReaction[Evt, Cmd] extends Reaction[Evt, Cmd] {
    def core: Process[Future, Cmd]
  }

  case class Answer[Evt, Cmd](f: Future[Cmd]) extends Reaction[Evt, Cmd]
  case class ConsumeStream[Evt, Cmd](val core: Process[Future, Cmd]) extends StreamReaction[Evt, Cmd]
  case class ProduceStream[Evt, Cmd](val core: Process[Future, Cmd]) extends StreamReaction[Evt, Cmd]
  case class ReactToStream[Evt, Cmd](val core: Process[Future, Cmd]) extends StreamReaction[Evt, Cmd]

  trait Decider[Evt, Cmd] {
    def answer(f: ⇒ Future[Cmd]): Answer[Evt, Cmd] = Answer(f)

    def produce(p: ⇒ Process[Future, Cmd]): ProduceStream[Evt, Cmd] = ProduceStream(p)

    def react(p: ⇒ Process[Future, Cmd]): ReactToStream[Evt, Cmd] = ReactToStream(p)

    def consumeStream(p: ⇒ Process[Future, Cmd]): ConsumeStream[Evt, Cmd] = ConsumeStream(p)

    def consume = Consume

    def process: PartialFunction[Evt, Action]
  }
}