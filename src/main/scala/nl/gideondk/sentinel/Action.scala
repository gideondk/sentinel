package nl.gideondk.sentinel

import akka.stream.scaladsl.Source

import scala.concurrent.Future
import play.api.libs.iteratee._

trait Action

trait ProducerAction[E, C] extends Action

trait ConsumerAction extends Action

object ProducerAction {

  trait Reaction[E, C] extends ProducerAction[E, C]

  trait StreamReaction[E, C] extends Reaction[E, C]

  trait Signal[E, C] extends Reaction[E, C] {
    def f: E ⇒ Future[C]
  }

  object Signal {
    def apply[E, C](fun: E ⇒ Future[C]): Signal[E, C] = new Signal[E, C] {
      val f = fun
    }
  }

  trait ConsumeStream[E, C] extends StreamReaction[E, C] {
    def f: E ⇒ Source[E, Any] ⇒ Future[C]
  }

  object ConsumeStream {
    def apply[E, A <: E, B <: E, C](fun: A ⇒ Enumerator[B] ⇒ Future[C]): ConsumeStream[E, C] = new ConsumeStream[E, C] {
      val f = fun.asInstanceOf[E ⇒ Source[E, Any] ⇒ Future[C]]
    }
  }

  trait ProduceStream[E, C] extends StreamReaction[E, C] {
    def f: E ⇒ Future[Source[C, Any]]
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