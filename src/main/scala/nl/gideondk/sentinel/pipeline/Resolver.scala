package nl.gideondk.sentinel.pipeline

import akka.stream.Materializer
import nl.gideondk.sentinel.protocol.Action

trait Resolver[In] {
  def process(implicit mat: Materializer): PartialFunction[In, Action]
}

