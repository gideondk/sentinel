package nl.gideondk.sentinel

import akka.actor._
import scala.concurrent.Promise
import akka.util.ByteString

import scalaz._
import Scalaz._
import effect._

case class SentinelCommand(command: ByteString, promise: Promise[Any])