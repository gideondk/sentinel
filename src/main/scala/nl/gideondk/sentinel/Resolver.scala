package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream._

import ReceiverAction._
import TransmitterAction._

trait Resolver[Evt, Cmd]

trait ConsumeResolver[Evt, Cmd] extends Resolver[Evt, Cmd] {
  import ReceiverAction._

  def accept = AcceptSignal

  def acceptError = AcceptError

  def consumeStreamChunk = ConsumeStreamChunk

  def endStream = EndStream

  def consumeAndEndStream = ConsumeChunkAndEndStream

  def ignore = Ignore
}

trait ResponseResolver[Evt, Cmd] extends Resolver[Evt, Cmd] {
  import TransmitterAction._

  def answer(f: Evt ⇒ Future[Cmd]): Signal[Evt, Cmd] = Signal(f)

  //def error(f: Evt ⇒ Cmd): Error[Evt, Cmd] = Error(f)

  def consumeStream(f: Evt ⇒ Process[Future, Evt] ⇒ Future[Cmd]): ConsumeStream[Evt, Cmd] = ConsumeStream(f)

  def produceStream(f: Evt ⇒ Future[Process[Future, Cmd]]): ProduceStream[Evt, Cmd] = ProduceStream(f)

  def react(f: Evt ⇒ Future[Channel[Future, Evt, Cmd]]): ReactToStream[Evt, Cmd] = ReactToStream(f)
}

trait SentinelResolver[Evt, Cmd] extends ConsumeResolver[Evt, Cmd] with ResponseResolver[Evt, Cmd] {
  def process: PartialFunction[Evt, Action]
}