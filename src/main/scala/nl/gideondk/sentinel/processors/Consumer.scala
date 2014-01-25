package nl.gideondk.sentinel.processors

import scala.collection.immutable.Queue
import scala.concurrent._
import scala.concurrent.duration.DurationInt

import com.typesafe.config.Config

import akka.actor._
import akka.actor.ActorSystem.Settings
import akka.dispatch._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.pattern.ask
import akka.util.Timeout

import scalaz.stream._
import scalaz.stream.Process._

import scalaz.contrib.std.scalaFuture.futureInstance

import nl.gideondk.sentinel._
import CatchableFuture._
import Registration._

object Consumer {
  trait StreamConsumerMessage

  case object ReadyForStream extends StreamConsumerMessage
  case object StartingWithStream extends StreamConsumerMessage
  case object AskNextChunk extends StreamConsumerMessage
  case object RegisterStreamConsumer extends StreamConsumerMessage
  case object ReleaseStreamConsumer extends StreamConsumerMessage

  trait ConsumerData[Evt]

  case class ConsumerException[Evt](cause: Evt) extends Exception

  case class DataChunk[Evt](c: Evt) extends ConsumerData[Evt]
  case class ErrorChunk[Evt](c: Evt) extends ConsumerData[Evt]
  case class EndOfStream[Evt]() extends ConsumerData[Evt]
}

class Consumer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], streamChunkTimeout: Timeout = Timeout(5 seconds)) extends Actor with ActorLogging {
  import Registration._
  import Consumer._
  import ConsumerAction._

  import context.dispatcher

  var hooks = Queue[Promise[ConsumerData[Evt]]]()
  var buffer = Queue[Promise[ConsumerData[Evt]]]()

  var registrations = Queue[Registration[Evt, _]]()
  var currentPromise: Option[Promise[Evt]] = None

  var runningSource: Option[Process[Future, Evt]] = None

  def processAction(data: Evt, action: ConsumerAction) = {
      def handleConsumerData(cd: ConsumerData[Evt]) = {
        hooks.headOption match {
          case Some(x) ⇒
            x.success(cd)
            hooks = hooks.tail
          case None ⇒
            buffer :+= Promise.successful(cd)
        }
      }

    action match {
      case AcceptSignal ⇒
        handleConsumerData(DataChunk(data))
      case AcceptError ⇒
        handleConsumerData(ErrorChunk(data))

      case ConsumeStreamChunk ⇒
        handleConsumerData(DataChunk(data)) // Should eventually seperate data chunks and stream chunks for better socket consistency handling
      case EndStream ⇒
        handleConsumerData(EndOfStream[Evt]())
      case ConsumeChunkAndEndStream ⇒
        handleConsumerData(DataChunk(data))
        handleConsumerData(EndOfStream[Evt]())

      case Ignore ⇒ ()
    }
  }

  def popAndSetHook = {
    val worker = self
    val registration = registrations.head
    registrations = registrations.tail

    implicit val timeout = streamChunkTimeout

    registration match {
      case x: ReplyRegistration[Evt] ⇒ x.promise.completeWith((self ? AskNextChunk).mapTo[Promise[ConsumerData[Evt]]].flatMap(_.future.flatMap {
        _ match {
          case x: DataChunk[Evt] ⇒
            Future.successful(x.c)
          case x: ErrorChunk[Evt] ⇒
            Future.failed(ConsumerException(x.c))
        }
      }))
      case x: StreamReplyRegistration[Evt] ⇒
        val resource = actorResource((worker ? RegisterStreamConsumer).map(x ⇒ worker))((x: ActorRef) ⇒ (x ? ReleaseStreamConsumer).mapTo[Unit]) {
          (x: ActorRef) ⇒
            (x ? AskNextChunk).mapTo[Promise[ConsumerData[Evt]]].flatMap(_.future).flatMap {
              _ match {
                case x: EndOfStream[Evt] ⇒
                  Future.failed(End)
                case x: DataChunk[Evt] ⇒
                  Future.successful(x.c)
                case x: ErrorChunk[Evt] ⇒
                  Future.failed(ConsumerException(x.c))
              }
            }
        }
        runningSource = Some(resource)
        x.promise success resource
    }
  }

  def handleRegistrations: Receive = {
    case rc: Registration[Evt, _] ⇒
      registrations :+= rc
      if (runningSource.isEmpty && currentPromise.isEmpty) popAndSetHook
  }

  var behavior: Receive = handleRegistrations orElse {
    case RegisterStreamConsumer ⇒
      sender ! StartingWithStream

    case ReleaseStreamConsumer ⇒
      runningSource = None
      if (registrations.headOption.isDefined) popAndSetHook
      sender ! ()

    case AskNextChunk ⇒
      val promise = buffer.headOption match {
        case Some(p) ⇒
          buffer = buffer.tail
          p
        case None ⇒
          val p = Promise[ConsumerData[Evt]]()
          hooks :+= p
          p
      }
      sender ! promise

    case x: ConsumerActionAndData[Evt] ⇒ processAction(x.data, x.action)

  }

  override def postStop() = {
    hooks.foreach(_.failure(new Exception("Actor quit unexpectedly")))
  }

  def receive = behavior
}