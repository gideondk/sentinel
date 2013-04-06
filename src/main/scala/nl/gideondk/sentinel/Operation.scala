package nl.gideondk.sentinel

import akka.actor._
import scala.concurrent.Promise
import akka.util.ByteString

import scalaz._
import Scalaz._
import effect._

case class Operation[A, B](command: A, promise: Promise[B])