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

object Management {
  trait ManagementMessage
  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage

  case class RegisterReply[A](terminator: A ⇒ Boolean, includeTerminator: Boolean, promise: Promise[Process[Future, A]]) extends ManagementMessage
  case object ReplyRegistered extends ManagementMessage
}

