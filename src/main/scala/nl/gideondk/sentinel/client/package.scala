package nl.gideondk.sentinel

import akka.util.ByteString

import akka.actor.ActorRef
import scala.concurrent.Promise
import scalaz._
import Scalaz._
import effect._
import java.net.InetSocketAddress

final class AskableSentinelClient(val clientActorRef: ActorRef) extends AnyVal {
  def <~<[A](command: A): Task[A] = sendCommand[A, A](command)

  def sendCommand[B, A](command: A): Task[B] = Task {
    val promise = Promise[B]()
    clientActorRef ! Operation(command, promise)
    promise.future
  }

}

package object client {
  implicit def commandable(actorRef: ActorRef): AskableSentinelClient = new AskableSentinelClient(actorRef)
}