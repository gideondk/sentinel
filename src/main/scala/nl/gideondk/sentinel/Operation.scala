package nl.gideondk.sentinel

import scala.concurrent.Promise
import play.api.libs.iteratee._

trait SentinelCommand

case class Operation[A, B](command: A, promise: Promise[B]) extends SentinelCommand

case class StreamedOperation[A, B](stream: Enumerator[A], promise: Promise[B]) extends SentinelCommand