package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream.Process

trait Action

trait ResponderAction extends Action
trait ConsumerAction extends Action

object ResponderAction {
  trait Reaction[Evt, Cmd] extends ResponderAction

  trait StreamReaction[Evt, Cmd] extends Reaction[Evt, Cmd] {
    def futureProcess: Future[Process[Future, Cmd]]
  }

  case class Answer[Evt, Cmd](future: Future[Cmd]) extends Reaction[Evt, Cmd]
  case class Handle[Evt, Cmd]() extends Reaction[Evt, Cmd] // Same as answer, but don't send back response (cast)

  case class ConsumeStream[Evt, Cmd](val futureProcess: Future[Process[Future, Cmd]]) extends StreamReaction[Evt, Cmd]
  case class ProduceStream[Evt, Cmd](val futureProcess: Future[Process[Future, Cmd]]) extends StreamReaction[Evt, Cmd]
  case class ReactToStream[Evt, Cmd](val futureProcess: Future[Process[Future, Cmd]]) extends StreamReaction[Evt, Cmd]
}

object ConsumerAction {
  case object Consume extends ConsumerAction
  case object Ignore extends ConsumerAction
}
