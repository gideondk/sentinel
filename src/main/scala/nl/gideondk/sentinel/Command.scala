package nl.gideondk.sentinel

import scala.concurrent.{ Future, Promise }

import akka.actor.ActorRef

import play.api.libs.iteratee._

trait Registration[Evt, A] {
  def promise: Promise[A]
}

object Registration {
  case class ReplyRegistration[Evt](promise: Promise[Evt]) extends Registration[Evt, Evt]
  case class StreamReplyRegistration[Evt](promise: Promise[Enumerator[Evt]]) extends Registration[Evt, Enumerator[Evt]]
}

trait Command[Cmd, Evt] {
  def registration: Registration[Evt, _]
}

trait ServerCommand[Cmd, Evt]

trait Reply[Cmd]

object Command {
  import Registration._

  case class Ask[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd, Evt]
  case class Tell[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd, Evt]

  case class AskStream[Cmd, Evt](payload: Cmd, registration: StreamReplyRegistration[Evt]) extends Command[Cmd, Evt]
  case class SendStream[Cmd, Evt](stream: Enumerator[Cmd], registration: ReplyRegistration[Evt]) extends Command[Cmd, Evt]
}

object ServerCommand {
  case class AskAll[Cmd, Evt](payload: Cmd, promise: Promise[List[Evt]]) extends ServerCommand[Cmd, Evt]
}

object Reply {
  case class Response[Cmd](payload: Cmd) extends Reply[Cmd]
  case class StreamResponseChunk[Cmd](payload: Cmd) extends Reply[Cmd]
}

object Management {
  trait ManagementMessage
  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage
}

