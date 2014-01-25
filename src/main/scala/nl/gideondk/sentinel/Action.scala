package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream._

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
    def apply[E, C](fun: E ⇒ Future[C]): Signal[E, C] = new Signal[E, C] { val f = fun }
  }

  trait ConsumeStream[E, C] extends StreamReaction[E, C] {
    def f: E ⇒ Process[Future, E] ⇒ Future[C]
  }

  object ConsumeStream {
    def apply[E, C](fun: E ⇒ Process[Future, E] ⇒ Future[C]): ConsumeStream[E, C] = new ConsumeStream[E, C] { val f = fun }
  }

  trait ProduceStream[E, C] extends StreamReaction[E, C] {
    def f: E ⇒ Future[Process[Future, C]]
  }

  object ProduceStream {
    def apply[E, C](fun: E ⇒ Future[Process[Future, C]]): ProduceStream[E, C] = new ProduceStream[E, C] { val f = fun }
  }

}

case class ProducerActionAndData[Evt, Cmd](action: ProducerAction[Evt, Cmd], data: Evt)

object ConsumerAction {
  case object AcceptSignal extends ConsumerAction
  case object AcceptError extends ConsumerAction

  case object ConsumeStreamChunk extends ConsumerAction
  case object EndStream extends ConsumerAction
  case object ConsumeChunkAndEndStream extends ConsumerAction

  case object Ignore extends ConsumerAction
}

case class ConsumerActionAndData[Evt](action: ConsumerAction, data: Evt)