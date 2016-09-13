package nl.gideondk.sentinel

import akka.{Done, NotUsed, stream}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, RunnableGraph, Sink, Source, Tcp}
import akka.stream.stage._
import akka.util.ByteString

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class Host(host: String, port: Int)

object ClientStage {

  trait ConnectionClosedException

  case class ConnectionClosedWithReasonException(message: String, cause: Throwable) extends Exception(message, cause) with ConnectionClosedException

  case class ConnectionClosedWithoutReasonException(message: String) extends Exception(message) with ConnectionClosedException

  trait ConnectionEvent {
    def host: Host
  }

  case class LinkUp(host: Host) extends ConnectionEvent

  case class LinkDown(host: Host) extends ConnectionEvent

  case object NoConnectionsAvailableException extends Exception

}

import ClientStage._

class ClientStage[Cmd, Evt](connectionsPerHost: Int, maximumFailuresPerHost: Int, recoveryPeriod: FiniteDuration, processor: () => Processor[Cmd, Evt], protocol: () => BidiFlow[ByteString, Evt, Cmd, ByteString, Any])
                           (implicit system: ActorSystem, mat: ActorMaterializer) extends GraphStage[FanInShape2[ConnectionEvent, (Command[Cmd], Promise[Event[Evt]]), (Try[Event[Evt]], Promise[Event[Evt]])]] {

  type Context = Promise[Event[Evt]]

  val connectionEventIn = Inlet[ConnectionEvent]("ClientStage.ConnectionEvent.In")
  val commandIn = Inlet[(Command[Cmd], Context)]("ClientStage.Command.In")
  val eventOut = Outlet[(Try[Event[Evt]], Context)]("ClientStage.Event.Out")

  override def shape = new FanInShape2(connectionEventIn, commandIn, eventOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private val hosts = mutable.Map.empty[Host, Int]
    private val hostFailures = mutable.Map.empty[Host, Int]
    private val connectionPool = mutable.Queue.empty[Connection]
    private val failures = mutable.Queue.empty[(Try[Event[Evt]], Context)]
    private var antennaId = 0

    override def preStart() = {
      pull(connectionEventIn)
      pull(commandIn)
      schedulePeriodically(Done, recoveryPeriod)
    }

    def nextId() = {
      antennaId += 1;
      antennaId
    }

    def addHost(host: Host) = {
      if (!hosts.contains(host)) {
        hosts += (host -> 0)
        pullCommand(true)
      }
    }

    def ensureConnections() = {
      hosts
        .find(_._2 < connectionsPerHost)
        .foreach { case (host, connectionCount) =>
          val connection = Connection(host, nextId())
          connection.initialize()
          connectionPool.enqueue(connection)
          hosts(connection.host) = connectionCount + 1
        }

      pullCommand(false)
    }

    def pullCommand(shouldInitializeConnection: Boolean): Unit =
      if (hosts.isEmpty && isAvailable(commandIn)) {
        val (_, context) = grab(commandIn)
        failures.enqueue((Failure(NoConnectionsAvailableException), context))

        if (isAvailable(eventOut) && failures.nonEmpty) {
          push(eventOut, failures.dequeue())
        }

        pull(commandIn)
      } else if (isAvailable(commandIn)) {
        connectionPool.dequeueFirst(_.canBePushedForCommand) match {
          case Some(connection) =>
            val (command, context) = grab(commandIn)
            connection.pushCommand(command, context)
            connectionPool.enqueue(connection)
            pull(commandIn)

          case None => if (shouldInitializeConnection) ensureConnections()
        }
      }


    def connectionFailed(connection: Connection, cause: Throwable) = {
      val host = connection.host
      val totalFailure = hostFailures.getOrElse(host, 0) + 1
      hostFailures(host) = totalFailure

      system.log.warning(s"Connection ${connection.connectionId} to $host failed due to ${cause.getMessage}")

      if (hostFailures(host) >= maximumFailuresPerHost) {
        system.log.error(cause, s"Dropping $host, failed $totalFailure times")
        removeHost(host, Some(cause))
      } else {
        removeConnection(connection, Some(cause))
      }
    }

    def removeHost(host: Host, cause: Option[Throwable] = None) = {
      hosts.remove(host)
      hostFailures.remove(host)
      connectionPool.dequeueAll(_.host == host).foreach(_.close(cause))

      if (isAvailable(eventOut) && failures.nonEmpty) {
        push(eventOut, failures.dequeue())
      }

      pullCommand(true)
    }

    def removeConnection(connection: Connection, cause: Option[Throwable]) = {
      hosts(connection.host) = hosts(connection.host) - 1
      connectionPool.dequeueAll(_.connectionId == connection.connectionId).foreach(_.close(cause))

      if (isAvailable(eventOut) && failures.nonEmpty) {
        push(eventOut, failures.dequeue())
      }

      pullCommand(true)
    }

    setHandler(connectionEventIn, new InHandler {
      override def onPush() = {
        grab(connectionEventIn) match {
          case LinkUp(connection) => addHost(connection)
          case LinkDown(connection) => removeHost(connection)
        }
        pull(connectionEventIn)
      }

      override def onUpstreamFinish() = ()

      override def onUpstreamFailure(ex: Throwable) =
        failStage(throw new IllegalStateException(s"Stream for ConnectionEvents failed", ex))
    })

    setHandler(commandIn, new InHandler {
      override def onPush() = pullCommand(shouldInitializeConnection = true)

      override def onUpstreamFinish() = completeStage()

      override def onUpstreamFailure(ex: Throwable) =
        failStage(throw new IllegalStateException(s"Requests stream failed", ex))
    })

    setHandler(eventOut, new OutHandler {
      override def onPull() =
        if (failures.nonEmpty) push(eventOut, failures.dequeue())
        else {
          connectionPool
            .dequeueFirst(_.canBePulledForEvent)
            .foreach(connection => {
              if (isAvailable(eventOut)) {
                push(eventOut, connection.pullEvent)
              }
              connectionPool.enqueue(connection)
            })
        }

      override def onDownstreamFinish() = completeStage()
    })


    override def onTimer(timerKey: Any) = hostFailures.clear()

    case class Connection(host: Host, connectionId: Int) {
      connection =>
      private val connectionEventIn = new SubSinkInlet[Event[Evt]](s"Connection.[$host].[$connectionId].in")
      private val connectionCommandOut = new SubSourceOutlet[Command[Cmd]](s"Connection.[$host].[$connectionId].out")
      private val contexts = mutable.Queue.empty[Promise[Event[Evt]]]

      def canBePushedForCommand = connectionCommandOut.isAvailable

      def canBePulledForEvent = connectionEventIn.isAvailable

      def pushCommand(command: Command[Cmd], context: Promise[Event[Evt]]) = {
        contexts.enqueue(context)
        connectionCommandOut.push(command)
      }

      def pullEvent() = {
        val event = connectionEventIn.grab()
        val context = contexts.dequeue()
        connectionEventIn.pull()
        (Success(event), context)
      }

      def close(cause: Option[Throwable]) = {
        val exception = cause match {
          case Some(cause) => ConnectionClosedWithReasonException(s"Failure to process request to $host at antenna $connectionId", cause)
          case None => ConnectionClosedWithoutReasonException(s"Failure to process request to $host antenna $connectionId")
        }

        contexts.dequeueAll(_ => true).foreach(context => {
          failures.enqueue((Failure(exception), context))
        })

        connectionEventIn.cancel()
        connectionCommandOut.complete()
      }

      def initialize() = {
        connectionEventIn.setHandler(new InHandler {
          override def onPush() = if (isAvailable(eventOut)) {
            push(eventOut, connection.pullEvent)
          }

          override def onUpstreamFinish() = removeConnection(connection, None)

          override def onUpstreamFailure(reason: Throwable) = reason match {
            case t: TimeoutException => removeConnection(connection, Some(t))
            case _ => connectionFailed(connection, reason)
          }
        })

        connectionCommandOut.setHandler(new OutHandler {
          override def onPull() = pullCommand(shouldInitializeConnection = true)

          override def onDownstreamFinish() = ()
        })

        RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val pipeline = b.add(processor()
            .flow
            .atop(protocol().reversed)
            .join(Tcp().outgoingConnection(host.host, host.port))
          )

          connectionCommandOut.source ~> pipeline.in
          pipeline.out ~> connectionEventIn.sink

          stream.ClosedShape
        }).run()(subFusingMaterializer)

        connectionEventIn.pull()
      }
    }

  }
}
