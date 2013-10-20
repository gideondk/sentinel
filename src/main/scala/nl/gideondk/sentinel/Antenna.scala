package nl.gideondk.sentinel

import scala.collection.immutable.Queue

import com.typesafe.config.Config

import akka.actor._
import akka.actor.ActorSystem.Settings
import akka.dispatch._

import akka.io._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }

import processors._

class AntennaMailbox(settings: Settings, cfg: Config) extends UnboundedPriorityMailbox(
  PriorityGenerator {
    case x: akka.io.Tcp.Event            ⇒ 0
    case x: Management.ManagementMessage ⇒ 1
    case x: Registration[_]              ⇒ 1
    case x: Reply[_]                     ⇒ 2
    case x: Command[_]                   ⇒ 2
    case _                               ⇒ 10
  })

class Antenna[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], Resolver: SentinelResolver[Evt, Cmd]) extends Actor with ActorLogging {
  var commandQueue = Queue.empty[Cmd]

  def active(tcpHandler: ActorRef): Receive = {
    val responder = context.actorOf(Props(new Responder(init)))
    val consumer = context.actorOf(Props(new Consumer(init)))

    context watch responder
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

      case x: Reply.Response[Cmd] ⇒
        tcpHandler ! init.Command(x.payload)

      case x: Reply.StreamResponseChunk[Cmd] ⇒
        tcpHandler ! init.Command(x.payload)

      case x: Registration[Evt] ⇒
        consumer ! x

      case init.Event(data) ⇒ {
        Resolver.process(data) match {
          case x: ResponderAction[Evt, Cmd] ⇒ responder ! ResponderActionAndData[Evt, Cmd](x, data)
          case x: ConsumerAction            ⇒ consumer ! ConsumerActionAndData[Evt](x, data)
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