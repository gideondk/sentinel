package nl.gideondk.sentinel

import scala.concurrent.Future

import scalaz.stream._

import ConsumerAction._
import ProducerAction._

trait Resolver[Evt, Cmd]

trait SentinelResolver[Evt, Cmd] {
  import ProducerAction._
  import ConsumerAction._

  def process: PartialFunction[Evt, Action]
}