package nl.gideondk.sentinel

import scala.concurrent.Promise
import play.api.libs.iteratee._

import scalaz.stream._
import scala.concurrent.Future
import scalaz.contrib.std.scalaFuture._

trait SentinelCommand[T] {
  def promise: Promise[T]
}

case class Signal[C, T](command: C, promise: Promise[T]) extends SentinelCommand[T]

case class UpStreamOperation[O, T](source: Process[Future, O], promise: Promise[T]) extends SentinelCommand[T]

//case class DownStreamOperation[A, B](command: A, val promise: Enumerator[B], terminator: B ⇒ Boolean) extends SentinelCommand
