package nl.gideondk.sentinel

import scala.concurrent.{ Future, Promise }

import akka.actor.ActorRef
import scalaz.stream.Process

trait Registration[Evt]

object Registration {
  case class ReplyRegistration[Evt](promise: Promise[Evt]) extends Registration[Evt]
  case class StreamReplyRegistration[Evt](terminator: Evt ⇒ Boolean, includeTerminator: Boolean, promise: Promise[Process[Future, Evt]]) extends Registration[Evt]
}

trait Command[Cmd]

object Command {
  import Registration._
  case class Ask[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd]
  case class Tell[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd]

  case class AskStream[Cmd, Evt](payload: Cmd, registration: StreamReplyRegistration[Evt]) extends Command[Cmd]
  case class SendStream[Cmd, Evt](command: Cmd, stream: Process[Future, Cmd], registration: ReplyRegistration[Evt]) extends Command[Cmd]

  case class Conversate[Cmd, Evt](command: Cmd, stream: Process[Future, Cmd], registration: StreamReplyRegistration[Evt]) extends Command[Cmd]

  case class Reply[Cmd](payload: Cmd) extends Command[Cmd]
  case class StreamReply[Cmd](payload: Cmd) extends Command[Cmd]
}

object Management {
  trait ManagementMessage
  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage
}

