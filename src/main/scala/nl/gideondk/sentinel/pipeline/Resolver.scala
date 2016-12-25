package nl.gideondk.sentinel.pipeline

import nl.gideondk.sentinel.protocol.Action

trait Resolver[In] {
  def process: PartialFunction[In, Action]
}

