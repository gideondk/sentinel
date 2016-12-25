package nl.gideondk.sentinel

import akka.actor.{ Actor, ActorSystem }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ BidiFlow, Flow, Tcp }
import akka.util.ByteString
import Protocol._

import scala.concurrent.{ ExecutionContext, Future }

object Pipeline {
  def create[Cmd, Evt](protocol: BidiFlow[ByteString, Evt, Cmd, ByteString, Any], resolver: Resolver[Evt], parallelism: Int, shouldReact: Boolean)(implicit ec: ExecutionContext) = {
    protocol >> Processor(resolver, parallelism, shouldReact).flow.reversed
  }
}
