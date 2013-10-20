package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream.Process

import ConsumerAction._
import ResponderAction._

trait Resolver[Evt, Cmd]

trait ConsumeResolver[Evt, Cmd] extends Resolver[Evt, Cmd] {
  import ConsumerAction._

  def consume = Consume

  def ignore = Ignore
}

trait ResponseResolver[Evt, Cmd] extends Resolver[Evt, Cmd] {
  import ResponderAction._
  def answer(f: ⇒ Future[Cmd]): Answer[Evt, Cmd] = Answer(f)

  def produce(p: ⇒ Future[Process[Future, Cmd]]): ProduceStream[Evt, Cmd] = ProduceStream(p)

  def react(p: ⇒ Future[Process[Future, Cmd]]): ReactToStream[Evt, Cmd] = ReactToStream(p)

  def consumeStream(p: ⇒ Future[Process[Future, Cmd]]): ConsumeStream[Evt, Cmd] = ConsumeStream(p)
}

trait SentinelResolver[Evt, Cmd] extends ConsumeResolver[Evt, Cmd] with ResponseResolver[Evt, Cmd] {
  def process: PartialFunction[Evt, Action]
}
