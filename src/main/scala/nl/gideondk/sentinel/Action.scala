package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream._

trait Action

trait TransmitterAction[Evt, Cmd] extends Action
trait ReceiverAction extends Action

object TransmitterAction {
  trait Reaction[Evt, Cmd] extends TransmitterAction[Evt, Cmd]
  trait StreamReaction[Evt, Cmd] extends Reaction[Evt, Cmd]

  case class Signal[Evt, Cmd](f: Evt ⇒ Future[Cmd]) extends Reaction[Evt, Cmd]
  case class Handle[Evt, Cmd](f: Evt ⇒ Unit) extends Reaction[Evt, Cmd] // Same as answer, but don't send back response (cast)

  case class ProduceStream[Evt, Cmd](f: Evt ⇒ Future[Process[Future, Cmd]]) extends StreamReaction[Evt, Cmd]
  case class ConsumeStream[Evt, Cmd](f: Evt ⇒ Process[Future, Evt] ⇒ Future[Cmd]) extends StreamReaction[Evt, Cmd]
  case class ReactToStream[Evt, Cmd](f: Evt ⇒ Future[Channel[Future, Evt, Cmd]]) extends StreamReaction[Evt, Cmd]
}

case class TransmitterActionAndData[Evt, Cmd](action: TransmitterAction[Evt, Cmd], data: Evt)

object ReceiverAction {
  case object Consume extends ReceiverAction
  case object EndStream extends ReceiverAction
  case object ConsumeAndEndStream extends ReceiverAction

  case object Ignore extends ReceiverAction
}

case class ReceiverActionAndData[Evt](action: ReceiverAction, data: Evt)