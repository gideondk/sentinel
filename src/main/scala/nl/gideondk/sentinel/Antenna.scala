package nl.gideondk.sentinel

import scala.collection.immutable.Queue

import com.typesafe.config.Config

import scala.concurrent.Future

import akka.actor._
import akka.actor.ActorSystem.Settings
import akka.dispatch._

import akka.io._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }

import processors._

class Antenna[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], Resolver: SentinelResolver[Evt, Cmd]) extends Actor with ActorLogging with Stash {
  import context.dispatcher

  def active(tcpHandler: ActorRef): Receive = {
    val receiver = context.actorOf(Props(new Receiver(init)), name = "resolver")
    val transmitter = context.actorOf(Props(new Transmitter(init)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"), name = "transmitter")

    context watch transmitter
    context watch receiver

      def handleTermination: Receive = {
        case x: Terminated ⇒ context.stop(self)
      }

      def highWaterMark: Receive = handleTermination orElse {
        case BackpressureBuffer.LowWatermarkReached ⇒
          unstashAll()
          context.unbecome()
        case _ ⇒
          stash()
      }

      def handleCommands: Receive = {
        case x: Command.Ask[Cmd, Evt] ⇒
          receiver ! x.registration
          tcpHandler ! init.Command(x.payload)

        case x: Command.AskStream[Cmd, Evt] ⇒
          receiver ! x.registration
          tcpHandler ! init.Command(x.payload)

        case x: Command.SendStream[Cmd, Evt] ⇒
          receiver ! x.registration
          transmitter ! TransmitterActionAndData(TransmitterAction.ProduceStream[Unit, Cmd](Unit ⇒ Future(x.stream)), ())

        case x: Command.Conversate[Cmd, Evt] ⇒

      }

      def handleReplies: Receive = {
        case x: Reply.Response[Cmd] ⇒
          tcpHandler ! init.Command(x.payload)

        case x: Reply.StreamResponseChunk[Cmd] ⇒
          tcpHandler ! init.Command(x.payload)
      }

    handleTermination orElse handleCommands orElse handleReplies orElse {
      case x: Registration[Evt, _] ⇒
        receiver ! x

      case init.Event(data) ⇒ {
        Resolver.process(data) match {
          case x: TransmitterAction[Evt, Cmd] ⇒ transmitter ! TransmitterActionAndData[Evt, Cmd](x, data)
          case x: ReceiverAction              ⇒ receiver ! ReceiverActionAndData[Evt](x, data)
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