package nl.gideondk.sentinel

import akka.actor.ActorRef
import akka.stream.scaladsl.Source

import scala.concurrent.Promise

trait Response[Evt]

case class SingularResponse[Evt](data: Evt) extends Response[Evt]

case class SingularErrorResponse[Evt](data: Evt) extends Response[Evt]

case class StreamResponse[Evt](source: Source[Evt, Any]) extends Response[Evt]

trait Registration[Evt, Resp <: Response[Evt]] {
  def promise: Promise[Resp]
}

object Registration {

  case class SingularResponseRegistration[Evt](promise: Promise[SingularResponse[Evt]]) extends Registration[Evt, SingularResponse[Evt]]

  case class StreamReplyRegistration[Evt](promise: Promise[StreamResponse[Evt]]) extends Registration[Evt, StreamResponse[Evt]]

}

trait Command[Cmd, Evt] {
  def registration: Registration[Evt, _]
}

trait ServerCommand[Cmd, Evt]

trait ServerMetric

trait Reply[Cmd]

object Command {

  import Registration._

  case class Ask[Cmd, Evt](payload: Cmd, registration: SingularResponseRegistration[Evt]) extends Command[Cmd, Evt]

  case class Tell[Cmd, Evt](payload: Cmd, registration: SingularResponseRegistration[Evt]) extends Command[Cmd, Evt]

  case class AskStream[Cmd, Evt](payload: Cmd, registration: StreamReplyRegistration[Evt]) extends Command[Cmd, Evt]

  case class SendStream[Cmd, Evt](stream: Source[Cmd, Any], registration: StreamReplyRegistration[Evt]) extends Command[Cmd, Evt]

}

object ServerCommand {

  case class AskAll[Cmd, Evt](payload: Cmd, promise: Promise[List[Evt]]) extends ServerCommand[Cmd, Evt]

  case class AskAllHosts[Cmd, Evt](payload: Cmd, promise: Promise[List[Evt]]) extends ServerCommand[Cmd, Evt]

  case class AskAny[Cmd, Evt](payload: Cmd, promise: Promise[Evt]) extends ServerCommand[Cmd, Evt]

}

object ServerMetric {

  case object ConnectedSockets extends ServerMetric

  case object ConnectedHosts extends ServerMetric

}

object Reply {

  case class Response[Cmd](payload: Cmd) extends Reply[Cmd]

  case class StreamResponseChunk[Cmd](payload: Cmd) extends Reply[Cmd]

}

object Management {

  trait ManagementMessage

  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage

}

