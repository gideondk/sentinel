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

import akka.actor.ActorSystem.Settings
import com.typesafe.config.Config
import akka.dispatch._

class AntennaMailbox(settings: Settings, cfg: Config) extends UnboundedPriorityMailbox(
  PriorityGenerator {
    case x: akka.io.Tcp.Event            ⇒ 0
    case x: Management.ManagementMessage ⇒ 1
    case x: Command[_]                   ⇒ 2
    case _                               ⇒ 10
  })

class Antenna[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], decider: Action.Decider[Evt, Cmd]) extends Actor with ActorLogging {
  var commandQueue = Queue.empty[Cmd]

  def active(tcpHandler: ActorRef): Receive = {
    val answerer = context.actorOf(Props(new RxProcessors.Answerer(init)))
    val consumer = context.actorOf(Props(new RxProcessors.Consumer(init)))

    context watch answerer
    context watch consumer

      def handleTermination: Receive = {
        case x: Terminated ⇒ context.stop(self)
      }

      def highWaterMark: Receive = handleTermination orElse {
        case init.Command(data) ⇒
          commandQueue.enqueue(data)
        case BackpressureBuffer.LowWatermarkReached ⇒
          def dequeueAndSend: Unit = {
              if (!commandQueue.isEmpty) {
                val c = commandQueue.head
                commandQueue = commandQueue.tail
                self ! init.Command(c)

                dequeueAndSend
              }
            }
          context.unbecome()
      }

    handleTermination orElse {
      case x: Command.Ask[Cmd, Evt] ⇒
        consumer ! x.registration
        tcpHandler ! init.Command(x.payload)

      case x: Command.AskStream[Cmd, Evt] ⇒
        consumer ! x.registration
        tcpHandler ! init.Command(x.payload)

      case x: Command.Reply[Cmd] ⇒
        tcpHandler ! init.Command(x.payload)

      case init.Event(data) ⇒ {
        decider.process(data) match {
          case x: Action.Answer[Evt, Cmd] ⇒ answerer ! x
          case Action.Consume             ⇒ consumer ! init.Event(data) // Pass through
        }
      }

      case BackpressureBuffer.HighWatermarkReached ⇒ {
        context.become(highWaterMark, false)
      }
    }
  }

  def receive = {
    case Management.RegisterTcpHandler(tcpHandler) ⇒
      context.become(active(tcpHandler))
  }
}