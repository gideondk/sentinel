package nl.gideondk.sentinel

import scala.concurrent.{ Future, Promise }

import akka.actor.ActorRef
import scalaz.stream._

trait Registration[Evt, A] {
  def promise: Promise[A]
}

object Registration {
  case class ReplyRegistration[Evt](promise: Promise[Evt]) extends Registration[Evt, Evt]
  case class StreamReplyRegistration[Evt](promise: Promise[Process[Future, Evt]]) extends Registration[Evt, Process[Future, Evt]]
  case class ChannelReplyRegistration[Cmd, Evt](promise: Promise[Channel[Future, Cmd, Evt]]) extends Registration[Evt, Channel[Future, Cmd, Evt]]
}

trait Command[Cmd, Evt] {
  def registration: Registration[Evt, _]
}

trait Reply[Cmd]

object Command {
  import Registration._

  case class Ask[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd, Evt]
  case class Tell[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd, Evt]

  case class AskStream[Cmd, Evt](payload: Cmd, registration: StreamReplyRegistration[Evt]) extends Command[Cmd, Evt]
  case class SendStream[Cmd, Evt](stream: Process[Future, Cmd], registration: ReplyRegistration[Evt]) extends Command[Cmd, Evt]

  case class Conversate[Cmd, Evt](command: Cmd, registration: ChannelReplyRegistration[Cmd, Evt]) extends Command[Cmd, Evt]
}

object Reply {
  case class Response[Cmd](payload: Cmd) extends Reply[Cmd]
  case class StreamResponseChunk[Cmd](payload: Cmd) extends Reply[Cmd]
}

object Management {
  trait ManagementMessage
  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage
}

