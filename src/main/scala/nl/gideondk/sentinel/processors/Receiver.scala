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

object Receiver {
  trait StreamReceiverMessage

  case object ReadyForStream extends StreamReceiverMessage
  case object StartingWithStream extends StreamReceiverMessage
  case object AskNextChunk extends StreamReceiverMessage
  case object RegisterStreamReceiver extends StreamReceiverMessage
  case object ReleaseStreamReceiver extends StreamReceiverMessage

  trait ReceiverData[Evt]

  case class ReceiverException[Evt](cause: Evt) extends Exception

  case class DataChunk[Evt](c: Evt) extends ReceiverData[Evt]
  case class ErrorChunk[Evt](c: Evt) extends ReceiverData[Evt]
  case class EndOfStream[Evt]() extends ReceiverData[Evt]
}

class Receiver[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], streamChunkTimeout: Timeout = Timeout(5 seconds)) extends Actor with ActorLogging {
  import Registration._
  import Receiver._
  import ReceiverAction._

  import context.dispatcher

  var hooks = Queue[Promise[ReceiverData[Evt]]]()
  var buffer = Queue[Promise[ReceiverData[Evt]]]()

  var registrations = Queue[Registration[Evt, _]]()
  var currentPromise: Option[Promise[Evt]] = None

  var runningSource: Option[Process[Future, Evt]] = None

  def processAction(data: Evt, action: ReceiverAction) = {
      def handleReceiverData(cd: ReceiverData[Evt]) = {
        hooks.headOption match {
          case Some(x) ⇒
            x.success(cd)
            hooks = hooks.tail
          case None ⇒
            buffer :+= Promise.successful(cd)
        }
      }

    //println(action)
    action match {
      case AcceptSignal ⇒
        handleReceiverData(DataChunk(data))
      case AcceptError ⇒
        handleReceiverData(ErrorChunk(data))

      case ConsumeStreamChunk ⇒
        handleReceiverData(DataChunk(data)) // Should eventually seperate data chunks and stream chunks for better socket consistency handling
      case EndStream ⇒
        handleReceiverData(EndOfStream[Evt]())
      case ConsumeChunkAndEndStream ⇒
        handleReceiverData(DataChunk(data))
        handleReceiverData(EndOfStream[Evt]())

      case Ignore ⇒ ()
    }
  }

  def popAndSetHook = {
    val worker = self
    val registration = registrations.head
    registrations = registrations.tail

    implicit val timeout = streamChunkTimeout

    registration match {
      case x: ReplyRegistration[Evt] ⇒ x.promise.completeWith((self ? AskNextChunk).mapTo[Promise[ReceiverData[Evt]]].flatMap(_.future.flatMap {
        _ match {
          case x: DataChunk[Evt] ⇒
            Future.successful(x.c)
          case x: ErrorChunk[Evt] ⇒
            Future.failed(ReceiverException(x.c))
        }
      }))
      case x: StreamReplyRegistration[Evt] ⇒
        val resource = actorResource((worker ? RegisterStreamReceiver).map(x ⇒ worker))((x: ActorRef) ⇒ (x ? ReleaseStreamReceiver).mapTo[Unit]) {
          (x: ActorRef) ⇒
            (x ? AskNextChunk).mapTo[Promise[ReceiverData[Evt]]].flatMap(_.future).flatMap {
              _ match {
                case x: EndOfStream[Evt] ⇒
                  Future.failed(End)
                case x: DataChunk[Evt] ⇒
                  Future.successful(x.c)
                case x: ErrorChunk[Evt] ⇒
                  Future.failed(ReceiverException(x.c))
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
    case RegisterStreamReceiver ⇒
      sender ! StartingWithStream

    case ReleaseStreamReceiver ⇒
      runningSource = None
      if (registrations.headOption.isDefined) popAndSetHook
      sender ! ()

    case AskNextChunk ⇒
      val promise = buffer.headOption match {
        case Some(p) ⇒
          buffer = buffer.tail
          p
        case None ⇒
          val p = Promise[ReceiverData[Evt]]()
          hooks :+= p
          p
      }
      sender ! promise

    case x: ReceiverActionAndData[Evt] ⇒ processAction(x.data, x.action)

  }

  override def postStop() = {
    hooks.foreach(_.failure(new Exception("Actor quit unexpectedly")))
  }

  def receive = behavior
}