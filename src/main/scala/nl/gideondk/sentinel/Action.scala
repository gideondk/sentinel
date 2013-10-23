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

  case class ConsumeStream[Evt, Cmd](f: Evt ⇒ Process[Future, Evt] ⇒ Future[Cmd]) extends StreamReaction[Evt, Cmd]

  case class ProduceStream[Evt, Cmd](f: Evt ⇒ Future[Process[Future, Cmd]]) extends StreamReaction[Evt, Cmd]

  case class ReactToStream[Evt, Cmd](f: Evt ⇒ Future[Channel[Future, Evt, Cmd]]) extends StreamReaction[Evt, Cmd]
}

case class TransmitterActionAndData[Evt, Cmd](action: TransmitterAction[Evt, Cmd], data: Evt)

object ReceiverAction {
  case object AcceptSignal extends ReceiverAction
  case object AcceptError extends ReceiverAction

  case object ConsumeStreamChunk extends ReceiverAction
  case object EndStream extends ReceiverAction
  case object ConsumeChunkAndEndStream extends ReceiverAction

  case object Ignore extends ReceiverAction
}

case class ReceiverActionAndData[Evt](action: ReceiverAction, data: Evt)