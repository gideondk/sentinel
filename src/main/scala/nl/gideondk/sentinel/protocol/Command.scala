package nl.gideondk.sentinel.protocol

import akka.actor.ActorRef
import akka.stream.scaladsl.Source

import scala.concurrent.Promise

trait Event[A]

case class SingularEvent[A](data: A) extends Event[A]

case class SingularErrorEvent[A](data: A) extends Event[A]

case class StreamEvent[A](chunks: Source[A, Any]) extends Event[A]

trait Registration[A, E <: Event[A]] {
  def promise: Promise[E]
}

object Registration {

  case class SingularResponseRegistration[A](promise: Promise[SingularEvent[A]]) extends Registration[A, SingularEvent[A]]

  case class StreamReplyRegistration[A](promise: Promise[StreamEvent[A]]) extends Registration[A, StreamEvent[A]]

}

trait Command[Out]

case class SingularCommand[Out](payload: Out) extends Command[Out]

case class StreamingCommand[Out](stream: Source[Out, Any]) extends Command[Out]

trait ServerCommand[Out, In]

trait ServerMetric

object Command {

  //  case class Ask[Out](payload: Out) extends Command[Out]

  object Ask

  //  case class Tell[Out](payload: Out) extends Command[Out]
  //
  //  case class AskStream[Out](payload: Out) extends Command[Out]
  //
  //  case class SendStream[Out](stream: Source[Out, Any]) extends Command[Out]

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

//object Reply {
//
//  case class Response[Cmd](payload: Cmd) extends Reply[Cmd]
//
//  case class StreamResponseChunk[Cmd](payload: Cmd) extends Reply[Cmd]
//
//}

object Management {

  trait ManagementMessage

  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage

}

