package nl.gideondk.sentinel

import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import akka.actor._
import akka.io.BackpressureBuffer
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import scalaz.stream._
import scalaz.stream.Process._
import scala.util.Try
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import nl.gideondk.sentinel._
import scala.concurrent.ExecutionContext
import akka.dispatch._
import scalaz._
import Scalaz._
import com.typesafe.config.Config
import akka.actor.ActorSystem.Settings

import scala.concurrent.Future
import scalaz.contrib.std.scalaFuture._
import nl.gideondk.sentinel.CatchableFuture._

import Action._

object Consumer {
  trait StreamConsumerMessage

  case object ReadyForStream extends StreamConsumerMessage
  case object StartingWithStream extends StreamConsumerMessage
  case object AskNextChunk extends StreamConsumerMessage
  case object RegisterStreamConsumer extends StreamConsumerMessage
  case object ReleaseStreamConsumer extends StreamConsumerMessage

  class ConsumerMailbox(settings: Settings, cfg: Config) extends UnboundedPriorityMailbox(
    PriorityGenerator {
      case x: StreamConsumerMessage        ⇒ 0
      case x: Management.ManagementMessage ⇒ 1
      case _                               ⇒ 10
    })

  def consumerResource[O](acquire: Future[ActorRef])(release: ActorRef ⇒ Future[Unit])(step: ActorRef ⇒ Future[O])(terminator: O ⇒ Boolean, includeTerminator: Boolean)(implicit context: ExecutionContext): Process[Future, O] = {
      def go(step: Future[O], onExit: Process[Future, O]): Process[Future, O] =
        await[Future, O, O](step)(
          o ⇒ {
            if (terminator(o)) {
              if (includeTerminator) {
                emit(o) ++ go(Future.failed(End), onExit)
              } else {
                go(Future.failed(End), onExit)
              }
            } else {
              emit(o) ++ go(step, onExit)
            }
          }, onExit, onExit)

    await(acquire)(r ⇒ {
      val onExit = eval(release(r)).drain
      go(step(r), onExit)
    }, halt, halt)
  }
}

class Consumer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], streamChunkTimeout: Timeout = Timeout(5 seconds)) extends Actor with ActorLogging {
  import Registration._
  import Consumer._

  import context.dispatcher

  var hooks = Queue[Promise[Evt]]()
  var buffer = Queue[Promise[Evt]]()

  var registrations = Queue[Registration[Evt]]()
  var currentPromise: Option[Promise[Evt]] = None

  var runningSource: Option[Process[Future, Evt]] = None

  def popAndSetHook = {
    val worker = self
    val registration = registrations.head
    registrations = registrations.tail

    implicit val timeout = streamChunkTimeout

    registration match {
      case x: ReplyRegistration[Evt] ⇒ x.promise.completeWith((self ? AskNextChunk).mapTo[Promise[Evt]].flatMap(_.future))
      case x: StreamReplyRegistration[Evt] ⇒
        val resource = consumerResource((worker ? RegisterStreamConsumer).map(x ⇒ worker))((x: ActorRef) ⇒ (x ? ReleaseStreamConsumer).mapTo[Unit])((x: ActorRef) ⇒
          (x ? AskNextChunk).mapTo[Promise[Evt]].flatMap(_.future))(x.terminator, x.includeTerminator)

        runningSource = Some(resource)
        x.promise success resource
    }
  }

  def handleRegistrations: Receive = {
    case rc: Registration[Evt] ⇒
      registrations :+= rc
      if (runningSource.isEmpty && currentPromise.isEmpty) popAndSetHook
  }

  var behavior: Receive = handleRegistrations orElse {
    case ReadyForStream ⇒
      sender ! StartingWithStream

    case ReleaseStreamConsumer ⇒
      runningSource = None
      if (hooks.headOption.isDefined) popAndSetHook
      sender ! ()

    case AskNextChunk ⇒
      val promise = buffer.headOption match {
        case Some(p) ⇒
          buffer = buffer.tail
          p
        case None ⇒
          val p = Promise[Evt]()
          hooks :+= p
          p
      }
      sender ! promise

    case init.Event(data) ⇒
      hooks.headOption match {
        case Some(x) ⇒
          x.success(data)
          hooks = hooks.tail
        case None ⇒
          buffer :+= Promise.successful(data)
      }

  }

  override def postStop() = {
    hooks.foreach(_.failure(new Exception("Actor quit unexpectedly")))
  }

  def receive = behavior
}