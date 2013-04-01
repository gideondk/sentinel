package nl.gideondk.sentinel

import akka.actor._
import scala.concurrent.Promise
import akka.util.ByteString

import scalaz._
import Scalaz._
import effect._

trait SentinelCommand {  
  def command: ByteString
  def promise: Promise[Any]
}

object SentinelCommand {
	def apply(c: ByteString, p: Promise[Any]) = new SentinelCommand {
		val command = c
		val promise = p
	}
}