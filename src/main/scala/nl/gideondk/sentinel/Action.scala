package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream._

trait Action

trait ResponderAction[Evt, Cmd] extends Action
trait ConsumerAction extends Action

object ResponderAction {
  trait Reaction[Evt, Cmd] extends ResponderAction[Evt, Cmd]
  trait StreamReaction[Evt, Cmd] extends Reaction[Evt, Cmd]

  case class Answer[Evt, Cmd](f: Evt ⇒ Future[Cmd]) extends Reaction[Evt, Cmd]
  case class Handle[Evt, Cmd](f: Evt ⇒ Unit) extends Reaction[Evt, Cmd] // Same as answer, but don't send back response (cast)

  case class ProduceStream[Evt, Cmd](f: Evt ⇒ Future[Process[Future, Cmd]]) extends StreamReaction[Evt, Cmd]
  case class ConsumeStream[Evt, Cmd](f: Evt ⇒ Process[Future, Evt] ⇒ Future[Cmd]) extends StreamReaction[Evt, Cmd]
  case class ReactToStream[Evt, Cmd](f: Evt ⇒ Future[Channel[Future, Evt, Cmd]]) extends StreamReaction[Evt, Cmd]
}

case class ResponderActionAndData[Evt, Cmd](action: ResponderAction[Evt, Cmd], data: Evt)

object ConsumerAction {
  case object Consume extends ConsumerAction
  case object EndStream extends ConsumerAction
  case object ConsumeAndEndStream extends ConsumerAction

  case object Ignore extends ConsumerAction
}

case class ConsumerActionAndData[Evt](action: ConsumerAction, data: Evt)