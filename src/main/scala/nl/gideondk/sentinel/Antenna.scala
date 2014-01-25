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
    val consumer = context.actorOf(Props(new Consumer(init)), name = "resolver")
    val producer = context.actorOf(Props(new Producer(init)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"), name = "producer")

    context watch producer
    context watch consumer

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
          consumer ! x.registration
          tcpHandler ! init.Command(x.payload)

        case x: Command.AskStream[Cmd, Evt] ⇒
          consumer ! x.registration
          tcpHandler ! init.Command(x.payload)

        case x: Command.SendStream[Cmd, Evt] ⇒
          consumer ! x.registration
          producer ! ProducerActionAndData(ProducerAction.ProduceStream[Unit, Cmd](Unit ⇒ Future(x.stream)), ())
      }

      def handleReplies: Receive = {
        case x: Reply.Response[Cmd] ⇒
          tcpHandler ! init.Command(x.payload)

        case x: Reply.StreamResponseChunk[Cmd] ⇒
          tcpHandler ! init.Command(x.payload)
      }

    handleTermination orElse handleCommands orElse handleReplies orElse {
      case x: Registration[Evt, _] ⇒
        consumer ! x

      case init.Event(data) ⇒ {
        Resolver.process(data) match {
          case x: ProducerAction[Evt, Cmd] ⇒ producer ! ProducerActionAndData[Evt, Cmd](x, data)
          case x: ConsumerAction           ⇒ consumer ! ConsumerActionAndData[Evt](x, data)
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