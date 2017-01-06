package nl.gideondk.sentinel.protocol

import akka.stream.scaladsl.Source

import scala.concurrent.Future

trait Action

trait ProducerAction[E, C] extends Action

trait ConsumerAction extends Action

object ProducerAction {

  trait Reaction[E, C] extends ProducerAction[E, C]

  trait StreamReaction[E, C] extends Reaction[E, C]

  trait Signal[In, Out] extends Reaction[In, Out] {
    def f: In ⇒ Future[Out]
  }

  trait ConsumeStream[E, C] extends StreamReaction[E, C] {
    def f: Source[E, Any] ⇒ Future[C]
  }

  trait ProduceStream[E, C] extends StreamReaction[E, C] {
    def f: E ⇒ Future[Source[C, Any]]
  }

  trait ProcessStream[E, C] extends StreamReaction[E, C] {
    def f: Source[E, Any] ⇒ Future[Source[C, Any]]
  }

  object Signal {
    def apply[E, C](fun: E ⇒ Future[C]): Signal[E, C] = new Signal[E, C] {
      val f = fun
    }
  }

  object ConsumeStream {
    def apply[Evt, Cmd](fun: Source[Evt, Any] ⇒ Future[Cmd]): ConsumeStream[Evt, Cmd] = new ConsumeStream[Evt, Cmd] {
      val f = fun
    }
  }

  object ProduceStream {
    def apply[E, C](fun: E ⇒ Future[Source[C, Any]]): ProduceStream[E, C] = new ProduceStream[E, C] {
      val f = fun
    }
  }

}

case class ProducerActionAndData[Evt, Cmd](action: ProducerAction[Evt, Cmd], data: Evt)

object ConsumerAction {

  case object AcceptSignal extends ConsumerAction

  case object AcceptError extends ConsumerAction

  case object StartStream extends ConsumerAction

  case object ConsumeStreamChunk extends ConsumerAction

  case object EndStream extends ConsumerAction

  case object ConsumeChunkAndEndStream extends ConsumerAction

  case object Ignore extends ConsumerAction

}

case class ConsumerActionAndData[Evt](action: ConsumerAction, data: Evt)