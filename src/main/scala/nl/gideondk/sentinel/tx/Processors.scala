package nl.gideondk.sentinel.tx

import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import akka.actor._
import akka.io.BackpressureBuffer
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import scalaz.stream._
import scalaz.stream.Process._
import nl.gideondk.sentinel.Task
import scala.util.Try
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout