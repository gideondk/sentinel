package nl.gideondk.sentinel.processors

import scala.collection.immutable.Queue
import scala.concurrent._
import scala.concurrent.duration.DurationInt

import akka.actor._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.pattern.ask
import akka.util.Timeout

import play.api.libs.iteratee._

import nl.gideondk.sentinel._

object Consumer {

  trait StreamConsumerMessage

  case object ReadyForStream extends StreamConsumerMessage

  case object StartingWithStream extends StreamConsumerMessage

  case object AskNextChunk extends StreamConsumerMessage

  case object RegisterStreamConsumer extends StreamConsumerMessage

  case object ReleaseStreamConsumer extends StreamConsumerMessage

  case object TimeoutStreamConsumer extends StreamConsumerMessage

  trait ConsumerData[Evt]

  case class ConsumerException[Evt](cause: Evt) extends Exception {
    override def toString() = "ConsumerException(" + cause + ")"
  }

  case class DataChunk[Evt](c: Evt) extends ConsumerData[Evt]

  case class StreamChunk[Evt](c: Evt) extends ConsumerData[Evt]

  case class ErrorChunk[Evt](c: Evt) extends ConsumerData[Evt]

  case class EndOfStream[Evt]() extends ConsumerData[Evt]

}

class StreamHandler[Cmd, Evt](streamConsumerTimeout: Timeout = Timeout(10 seconds)) extends Actor with ActorLogging {
  import Registration._
  import Consumer._
  import ConsumerAction._
  import context.dispatcher

  context.setReceiveTimeout(streamConsumerTimeout.duration)

  var hook: Option[Promise[ConsumerData[Evt]]] = None
  var buffer = Queue[ConsumerData[Evt]]()

  override def postStop() = {
    hook.foreach(_.failure(new Exception("Actor quit unexpectedly")))
  }

  def receive: Receive = {
    case ReleaseStreamConsumer ⇒
      context.stop(self)
      sender ! ()

    case AskNextChunk ⇒
      sender ! nextStreamChunk

    case chunk: ConsumerData[Evt] ⇒
      hook match {
        case Some(x) ⇒
          x.success(chunk)
          hook = None
        case None ⇒
          buffer :+= chunk
      }

    case ReceiveTimeout ⇒ {
      context.stop(self)
    }

  }

  def nextStreamChunk = {
    buffer.headOption match {
      case Some(c) ⇒
        buffer = buffer.tail
        Promise[ConsumerData[Evt]]().success(c)
      case None ⇒
        val p = Promise[ConsumerData[Evt]]()
        hook = Some(p)
        p
    }
  }
}

class Consumer[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt],
                         streamChunkTimeout: Timeout = Timeout(120 seconds),
                         streamConsumerTimeout: Timeout = Timeout(10 seconds)) extends Actor with ActorLogging {
  import Registration._
  import Consumer._
  import ConsumerAction._

  import context.dispatcher

  implicit val timeout = streamChunkTimeout

  var replyRegistrations = Queue[ReplyRegistration[Evt]]()
  var streamRegistrations = Queue[StreamReplyRegistration[Evt]]()

  var streamBuffer = Queue[ConsumerData[Evt]]()

  var currentRunningStream: Option[ActorRef] = None

  override def postStop() = {
    replyRegistrations.foreach(_.promise.failure(new Exception("Actor quit unexpectedly")))
    streamRegistrations.foreach(_.promise.failure(new Exception("Actor quit unexpectedly")))
  }

  def processAction(data: Evt, action: ConsumerAction) = {

      def handleConsumerData(cd: ConsumerData[Evt]) = {
        val registration = replyRegistrations.head
        replyRegistrations = replyRegistrations.tail

        registration.promise.completeWith(cd match {
          case x: DataChunk[Evt] ⇒
            Future.successful(x.c)
          case x: ErrorChunk[Evt] ⇒
            Future.failed(ConsumerException(x.c))
        })
      }

      def handleStreamData(cd: ConsumerData[Evt]) = {
        currentRunningStream match {
          case Some(x) ⇒
            cd match {
              case x: EndOfStream[Evt] ⇒ currentRunningStream = None
              case _                   ⇒ ()
            }

            x ! cd

          case None ⇒
            streamRegistrations.headOption match {
              case Some(registration) ⇒
                val streamHandler = context.actorOf(Props(new StreamHandler(streamConsumerTimeout)), name = "streamHandler-" + java.util.UUID.randomUUID.toString)
                currentRunningStream = Some(streamHandler)

                val worker = streamHandler

                // TODO: handle stream chunk timeout better
                val resource = Enumerator.generateM[Evt] {
                  (worker ? AskNextChunk).mapTo[Promise[ConsumerData[Evt]]].flatMap(_.future).flatMap {
                    _ match {
                      case x: EndOfStream[Evt] ⇒ (worker ? ReleaseStreamConsumer) flatMap (u ⇒ Future(None))
                      case x: StreamChunk[Evt] ⇒ Future(Some(x.c))
                      case x: ErrorChunk[Evt]  ⇒ (worker ? ReleaseStreamConsumer) flatMap (u ⇒ Future.failed(ConsumerException(x.c)))
                    }
                  }
                }

                  def dequeueStreamBuffer(): Unit = {
                    streamBuffer.headOption match {
                      case Some(x) ⇒
                        streamBuffer = streamBuffer.tail
                        x match {
                          case x: EndOfStream[Evt] ⇒
                            worker ! x
                          case x ⇒
                            worker ! x
                            dequeueStreamBuffer()
                        }
                      case None ⇒ ()
                    }
                  }

                dequeueStreamBuffer()
                worker ! cd

                streamRegistrations = streamRegistrations.tail
                registration.promise success resource

              case None ⇒
                streamBuffer :+= cd
            }
        }
      }

    action match {
      case AcceptSignal ⇒
        handleConsumerData(DataChunk(data))
      case AcceptError ⇒
        currentRunningStream match {
          case Some(x) ⇒ handleStreamData(ErrorChunk(data))
          case None    ⇒ handleConsumerData(ErrorChunk(data))
        }

      case ConsumeStreamChunk ⇒
        handleStreamData(StreamChunk(data))
      case EndStream ⇒
        handleStreamData(EndOfStream[Evt]())
      case ConsumeChunkAndEndStream ⇒
        handleStreamData(StreamChunk(data))
        handleStreamData(EndOfStream[Evt]())

      case Ignore ⇒ ()
    }
  }

  def handleRegistrations: Receive = {
    case rc: ReplyRegistration[Evt] ⇒
      replyRegistrations :+= rc

    case rc: StreamReplyRegistration[Evt] ⇒
      streamRegistrations :+= rc

  }

  var behavior: Receive = handleRegistrations orElse {
    case x: ConsumerActionAndData[Evt] ⇒
      processAction(x.data, x.action)

  }

  def receive = behavior
}