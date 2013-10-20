package nl.gideondk.sentinel

import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import akka.actor._
import akka.io.BackpressureBuffer
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import scalaz.stream._
import scalaz.stream.Process._

import scala.util.Try
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scalaz.contrib.std.scalaFuture._

trait Registration[Evt]

object Registration {
  case class ReplyRegistration[Evt](promise: Promise[Evt]) extends Registration[Evt]
  case class StreamReplyRegistration[Evt](terminator: Evt ⇒ Boolean, includeTerminator: Boolean, promise: Promise[Process[Future, Evt]]) extends Registration[Evt]
}

trait Command[Cmd]

object Command {
  import Registration._
  case class Ask[Cmd, Evt](payload: Cmd, registration: ReplyRegistration[Evt]) extends Command[Cmd]
  case class AskStream[Cmd, Evt](payload: Cmd, registration: StreamReplyRegistration[Evt]) extends Command[Cmd]

  case class Reply[Cmd](payload: Cmd) extends Command[Cmd]
  case class StreamReply[Cmd](payload: Cmd) extends Command[Cmd]
}

object Management {
  trait ManagementMessage
  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage
}

