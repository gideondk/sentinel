package nl.gideondk.sentinel

import akka.stream.scaladsl.{ BidiFlow, Concat, Flow, GraphDSL, Source }
import akka.stream._
import akka.stream.actor.{ ActorPublisher, ActorSubscriber }
import akka.stream.stage.GraphStageLogic.EagerTerminateOutput
import akka.stream.stage.{ OutHandler, _ }
import akka.util.ByteString
import nl.gideondk.sentinel.ConsumerAction._

trait Resolver[In] {
  def process: PartialFunction[In, Action]
}

