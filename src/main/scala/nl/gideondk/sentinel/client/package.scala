package nl.gideondk.sentinel

import scala.concurrent.Promise
import play.api.libs.iteratee._

import akka.actor.{ ActorRef, actorRef2Scala }

final class AskableSentinelClient(val clientActorRef: ActorRef) extends AnyVal {
  def <~<[A](command: A): Task[A] = sendCommand[A, A](command)

  def sendCommand[B, A](command: A): Task[B] = Task {
    val promise = Promise[B]()
    clientActorRef ! Operation(command, promise)
    promise.future
  }

  def streamCommands[B, A](stream: Enumerator[A]): Task[B] = Task {
    val promise = Promise[B]()
    clientActorRef ! StreamedOperation(stream, promise)
    promise.future
  }
}

package object client {
  implicit def commandable(actorRef: ActorRef): AskableSentinelClient = new AskableSentinelClient(actorRef)
}