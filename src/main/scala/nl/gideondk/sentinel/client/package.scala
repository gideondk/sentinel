package nl.gideondk.sentinel

import akka.util.ByteString

import akka.actor.ActorRef
import scala.concurrent.Promise
import scalaz._
import Scalaz._
import effect._
import java.net.InetSocketAddress

final class AskableSentinelClient(val clientActorRef: ActorRef) extends AnyVal {
  def <~<[A](command: A): ValidatedFutureIO[A] = sendCommand[A, A](command)

  def sendCommand[B, A](command: A): ValidatedFutureIO[B] = {
    val ioAction = {
      val promise = Promise[B]()
      clientActorRef ! Operation(command, promise)
      promise
    }.point[IO]

    ValidatedFutureIO(ioAction.map(x ⇒ ValidatedFuture(x.future)))
  }
}

package object client {
  implicit def commandable(actorRef: ActorRef): AskableSentinelClient = new AskableSentinelClient(actorRef)
}