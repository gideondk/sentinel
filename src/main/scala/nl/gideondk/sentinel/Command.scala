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

trait Command[A]

//trait RespondingCommand[A, B] extends Command[A] {
//  def promise: 
//}

object Command {
  case class Ask[Cmd, Evt](payload: Cmd, val pp: Promise[Evt]) extends Command[Cmd]
  case class AskStream[Cmd, Evt](payload: Cmd, terminator: Evt ⇒ Boolean, includeTerminator: Boolean, val pp: Promise[Process[Future, Evt]]) extends Command[Cmd]
  //case class Ask[Cmd, Evt](payload: Cmd, terminator: Evt ⇒ Boolean, includeTerminator: Boolean, val pp: Promise[Process[Future, Evt]]) extends Command[Cmd]
  case class Reply[Cmd](payload: Cmd) extends Command[Cmd]

  //case class Stream[O, T](source: Process[Task, O], promise: Promise[T]) extends Command
}
