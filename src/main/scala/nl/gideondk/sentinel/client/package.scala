package nl.gideondk.sentinel

import akka.actor.ActorRef

package object client {
  implicit def commandable(actorRef: ActorRef): AskableSentinelClient = new AskableSentinelClient(actorRef)
}