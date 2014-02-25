package nl.gideondk.sentinel

trait Resolver[Evt, Cmd] {

  def process: PartialFunction[Evt, Action]
}