package nl.gideondk.sentinel

trait Resolver[Evt, Cmd]

trait SentinelResolver[Evt, Cmd] {

  def process: PartialFunction[Evt, Action]
}