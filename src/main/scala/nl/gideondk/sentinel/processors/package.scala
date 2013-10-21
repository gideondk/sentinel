package nl.gideondk.sentinel

import scala.concurrent._

import akka.actor._
import akka.actor.ActorSystem.Settings

import scalaz.stream._
import scalaz.stream.Process._

import Task._

package object processors {

  def actorResource[O](acquire: Task[ActorRef])(release: ActorRef ⇒ Task[Unit])(step: ActorRef ⇒ Future[O])(implicit context: ExecutionContext): Process[Future, O] = {
      def go(step: ⇒ Future[O], onExit: Process[Future, O]): Process[Future, O] =
        await[Future, O, O](step)(o ⇒ emit(o) ++ go(step, onExit), onExit, onExit)

    await(acquire)(r ⇒ {
      val onExit = await(Future { () })(_ ⇒ eval(release(r)).drain)
      go(step(r), onExit)
    }, halt, halt)
  }
}