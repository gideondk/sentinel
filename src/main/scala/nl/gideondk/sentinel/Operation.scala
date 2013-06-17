package nl.gideondk.sentinel

import scala.concurrent.Promise
import play.api.libs.iteratee._

case class Operation[A, B](command: A, promise: Promise[B])

case class StreamedOperation[A, B](stream: Enumerator[A], promise: Promise[B])