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

trait InternalConsumerMessage

class ConsumerMailbox(settings: Settings, cfg: Config) extends UnboundedPriorityMailbox(
  PriorityGenerator {
    case x: InternalConsumerMessage      ⇒ 0
    case x: Management.ManagementMessage ⇒ 1
    case _                               ⇒ 10
  })

object RxProcessors {
  import Action._

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

  class Consumer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt]) extends Actor with ActorLogging {
    import Registration._

    import context.dispatcher

    var hooks = Queue[Promise[Evt]]()
    var buffer = Queue[Promise[Evt]]()

    var registrations = Queue[Registration[Evt]]()

    //var registrations = Queue[Management.RegisterReply[Evt]]()

    var currentPromise: Option[Promise[Evt]] = None

    var runningSource: Option[Process[Future, Evt]] = None

    case object AskNextChunk extends InternalConsumerMessage
    case object RegisterSource extends InternalConsumerMessage
    case object ReleaseSource extends InternalConsumerMessage

    implicit val timeout = Timeout(5 seconds)

    def popAndSetHook = {
      val me = self
      val registration = registrations.head
      registrations = registrations.tail

      registration match {
        case x: ReplyRegistration[Evt] ⇒ x.promise.completeWith((self ? AskNextChunk).mapTo[Promise[Evt]].flatMap(_.future))
        case x: StreamReplyRegistration[Evt] ⇒
          val resource = consumerResource((me ? RegisterSource).map(x ⇒ self))((x: ActorRef) ⇒ (x ? ReleaseSource).mapTo[Unit])((x: ActorRef) ⇒
            (x ? AskNextChunk).mapTo[Promise[Evt]].flatMap(_.future))(x.terminator, x.includeTerminator)

          runningSource = Some(resource)
          x.promise success resource
      }

      // implicit val timeout = Timeout(5 seconds)

      // val resource = consumerResource((me ? RegisterSource).map(x ⇒ self))((x: ActorRef) ⇒ (x ? ReleaseSource).mapTo[Unit])((x: ActorRef) ⇒
      //   (x ? AskNextChunk).mapTo[Promise[Evt]].flatMap(_.future))(registration.terminator, registration.includeTerminator)

      // runningSource = Some(resource)
      // registration.promise success resource
    }

    var behavior: Receive = {
      case RegisterSource ⇒
        sender ! self

      case ReleaseSource ⇒
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

      case rc: Registration[Evt] ⇒
        registrations :+= rc
        if (runningSource.isEmpty && currentPromise.isEmpty) popAndSetHook

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

  class Answerer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt]) extends Actor with ActorLogging {
    import context.dispatcher

    case class HandleAsyncResult(response: Cmd)
    case object DequeueResponse

    var responseQueue = Queue.empty[Promise[HandleAsyncResult]]
    var behavior: Receive = handleRequestAndResponse

    def handleRequest: Receive = {
      case x: Answer[Evt, Cmd] ⇒
        val serverWorker = self
        val promise = Promise[HandleAsyncResult]()
        responseQueue :+= promise

        val fut = for {
          response ← x.f map (result ⇒ HandleAsyncResult(result))
        } yield {
          promise.success(response)
          serverWorker ! DequeueResponse
        }

        fut.onFailure {
          // If the future failed, message sequence isn't certain; tear down line to let client recover.
          case e ⇒
            log.error(e, e.getMessage)
            context.stop(self)
        }
    }

    def handleRequestAndResponse: Receive = handleRequest orElse {
      case x: HandleAsyncResult ⇒ context.parent ! Command.Reply(x.response)
      // x.response match {
      //   case Some(r) ⇒ context.parent ! Command.Reply(r)
      //   case None ⇒
      //     log.error("Stream didn't result in a value")
      //     context.stop(self)
      // }

      case DequeueResponse ⇒ {
          def dequeueAndSend: Unit = {
            if (!responseQueue.isEmpty && responseQueue.front.isCompleted) {
              // TODO: Should be handled a lot safer!
              val promise = responseQueue.head
              responseQueue = responseQueue.tail
              promise.future.value match {
                case Some(Success(v)) ⇒
                  self ! v
                  dequeueAndSend
                case Some(Failure(e)) ⇒
                  log.error(e, e.getMessage)
                  context.stop(self)
              }
            }

          }
        dequeueAndSend
      }
    }

    def receive = behavior
  }
}