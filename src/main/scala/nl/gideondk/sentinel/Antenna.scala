package nl.gideondk.sentinel

import akka.actor._
import akka.io.TcpPipelineHandler.{ Init, WithinActorContext }
import akka.io._
import nl.gideondk.sentinel.processors._
import scala.collection.immutable.Queue

import scala.concurrent.Future

class Antenna[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], resolver: Resolver[Evt, Cmd], allowPipelining: Boolean = true) extends Actor with ActorLogging with Stash {

  import context.dispatcher

  def active(tcpHandler: ActorRef): Receive = {
    val consumer = context.actorOf(Props(new Consumer(init)), name = "resolver")
    val producer = context.actorOf(Props(new Producer(init)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"), name = "producer")

    var commandQueue = Queue.empty[init.Command]
    var commandInProcess = false

    context watch tcpHandler
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

      def popCommand() = if (!commandQueue.isEmpty) {
        val cmd = commandQueue.head
        commandQueue = commandQueue.tail
        tcpHandler ! cmd
      } else {
        commandInProcess = false
      }

      def handleCommands: Receive = {
        case x: Command.Ask[Cmd, Evt] ⇒
          consumer ! x.registration

          val cmd = init.Command(x.payload)
          if (allowPipelining) tcpHandler ! cmd
          else if (commandInProcess) {
            commandQueue :+= cmd
          } else {
            commandInProcess = true
            tcpHandler ! cmd
          }

        case x: Command.AskStream[Cmd, Evt] ⇒
          consumer ! x.registration

          val cmd = init.Command(x.payload)
          if (allowPipelining) tcpHandler ! cmd
          else if (commandInProcess) {
            commandQueue :+= cmd
          } else {
            commandInProcess = true
            tcpHandler ! cmd
          }

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
        resolver.process(data) match {
          case x: ProducerAction[Evt, Cmd] ⇒ producer ! ProducerActionAndData[Evt, Cmd](x, data)

          case ConsumerAction.ConsumeStreamChunk ⇒
            consumer ! ConsumerActionAndData[Evt](ConsumerAction.ConsumeStreamChunk, data)

          case x: ConsumerAction ⇒
            consumer ! ConsumerActionAndData[Evt](x, data)
            if (!allowPipelining) popCommand()
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