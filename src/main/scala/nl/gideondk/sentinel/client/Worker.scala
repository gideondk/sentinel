// package nl.gideondk.sentinel.client

// import java.net.InetSocketAddress
// import akka.actor._
// import akka.io._
// import akka.io.Tcp._
// import akka.io.TcpPipelineHandler._
// import akka.util.ByteString
// import nl.gideondk.sentinel._
// import play.api.libs.iteratee._
// import scala.concurrent.Promise
// import scala.util.{ Success, Failure }
// import scala.collection.immutable.Queue
// import scalaz.stream._

// import Process._
// import process1._
// import akka.pattern._
// import scalaz._
// import Scalaz._

// import akka.util.Timeout
// import scala.concurrent.duration._

// object SentinelClientWorker {
//   case class ConnectToHost(address: InetSocketAddress)
//   case object TcpActorDisconnected
//   case object UpstreamFinished

//   case class NoConnectionAvailable(reason: String) extends Throwable(reason)

//   class OperationHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef) extends Actor with ActorLogging {
//     context watch connection

//     var behavior: Receive = {
//       case init.Event(data) ⇒
//         val pr = promises.head
//         promises = promises.tail
//         pr.success(data)

//       case o: Operation[Cmd, Evt] ⇒
//         promises :+= o.promise
//         connection ! init.Command(o.command)
//     }

//     var promises = Queue[Promise[Evt]]()

//     override def postStop() = {
//       promises.foreach(_.failure(new Exception("Actor quit unexpectedly")))
//     }

//     def receive = behavior
//   }

//   class UpStreamHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef) extends Actor with ActorLogging with Stash {
//     import context.dispatcher

//     context watch connection

//     var promises = Queue[Promise[Evt]]()

//     override def postStop = {
//       promises.foreach(_.failure(new Exception("Actor quit unexpectedly")))
//     }

//     def handleResponses: Receive = {
//       case init.Event(data) ⇒
//         val pr = promises.head
//         promises = promises.tail
//         pr.success(data)
//     }

//     def receive: Receive = handleResponses orElse {
//       case o: UpStreamOperation[Cmd, Evt] ⇒
//         promises :+= o.promise
//         context.become(handleOutgoingStream(o.source), discardOld = false)
//     }

//     def handleOutgoingStream(stream: Process[Task, Cmd]): Receive = {
//       case object StreamFinished
//       case object StreamFinishedIsOk
//       case object StreamReady

//       case object InitializeStream

//       case class StreamChunk(c: Cmd)
//       case object StreamChunkSent

//       implicit val timeout = Timeout(2 seconds)
//       val sink = Task.actorResource(self)((x: ActorRef) ⇒ Task(x ? InitializeStream))((x: ActorRef) ⇒ Task(x ? StreamFinished))((x: ActorRef) ⇒ ((c: Cmd) ⇒ Task(x ? StreamChunk(c)).map(x ⇒ ())).point[Task])
//       val a = (stream to sink)
//       val x = a.run
//       x.start

//       //      val sink = io.resource(self.point[STask])((x: ActorRef) => (x ! StreamFinished).point[STask])((x: ActorRef) => ((c: Cmd) => (x ! StreamChunk(c)).point[STask]).point[STask])
//       //      val a = stream to sink
//       //      a.run

//       handleResponses orElse {
//         case InitializeStream ⇒
//           sender ! StreamReady

//         case StreamChunk(x) ⇒
//           connection ! init.Command(x)
//           sender ! StreamChunkSent

//         case StreamFinished ⇒
//           context.parent ! UpstreamFinished
//           unstashAll()
//           context.unbecome()
//           sender ! StreamFinishedIsOk

//         case scala.util.Failure(e: Throwable) ⇒
//           log.error(e.getMessage)
//           context.stop(self)

//         case BackpressureBuffer.HighWatermarkReached ⇒
//           context.become(handleResponses orElse {
//             case BackpressureBuffer.LowWatermarkReached ⇒
//               unstashAll()
//               context.unbecome()
//             case _: SentinelCommand[_] | _: StreamChunk | StreamFinished ⇒ stash()
//           }, discardOld = false)
//         case _: SentinelCommand[_] ⇒ stash()
//       }
//     }
//   }
// }

// class SentinelClientWorker[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
//                                      workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging with Stash {
//   import SentinelClientWorker._

//   val tcp = akka.io.IO(Tcp)(context.system)
//   var receiverQueue = Queue.empty[ActorRef]

//   override def preStart = tcp ! Tcp.Connect(address)

//   def disconnected: Receive = {
//     case Connected(remoteAddr, localAddr) ⇒
//       val init = TcpPipelineHandler.withLogger(log,
//         stages >>
//           new TcpReadWriteAdapter)

//       val handler = context.actorOf(TcpPipelineHandler.props(init, sender, self).withDeploy(Deploy.local))
//       context watch handler

//       sender ! Register(handler)
//       unstashAll()
//       context.become(connected(init, handler))

//     case CommandFailed(cmd: Command) ⇒
//       context.stop(self) // Bit harsh at the moment, but should trigger reconnect and probably do better next time...

//     case x: SentinelCommand[_] ⇒
//       x.promise.failure(NoConnectionAvailable("Client has not yet been connected to a endpoint"))

//     case _ ⇒ stash()
//   }

//   def connected(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = {
//     val operationHandler = context.actorOf(Props(new OperationHandler(init, connection)))
//     val upstreamHandler = context.actorOf(Props(new UpStreamHandler(init, connection)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))

//     var behavior: Receive = { case _ ⇒ () }
//     var previousBehavior: Receive = { case _ ⇒ () }

//     context watch operationHandler
//     context watch upstreamHandler

//       def handleResponses: Receive = {
//         case x: init.Event ⇒
//           val actor = receiverQueue.head
//           receiverQueue = receiverQueue.tail
//           actor.forward(x)
//       }

//       def handleHighWaterMark: Receive = {
//         case BackpressureBuffer.HighWatermarkReached ⇒
//           context.children.foreach(_ ! BackpressureBuffer.HighWatermarkReached)
//           previousBehavior = behavior
//           behavior = handleResponses orElse {
//             case BackpressureBuffer.LowWatermarkReached ⇒
//               context.children.foreach(_ ! BackpressureBuffer.LowWatermarkReached)
//               unstashAll()
//               behavior = previousBehavior
//             case _: SentinelCommand[_] ⇒ stash()
//           }
//       }

//       /* Upstream handler, stashes new requests until up stream is finished */
//       def handleUpstream: Receive = handleResponses orElse handleHighWaterMark orElse {
//         case UpstreamFinished ⇒
//           unstashAll()
//           context.unbecome()
//         case _ ⇒ stash()
//       }

//       def default: Receive = handleResponses orElse handleHighWaterMark orElse {
//         case o: Operation[Cmd, Evt] ⇒
//           receiverQueue :+= operationHandler
//           operationHandler forward o

//         case uso: UpStreamOperation[Cmd, Evt] ⇒
//           context.become(handleUpstream, discardOld = false)
//           receiverQueue :+= upstreamHandler
//           upstreamHandler forward uso

//         //case dso: DownStreamOperation[Cmd, Evt] ⇒

//         case Terminated(`connection`) ⇒
//           log.error(workerDescription + " has been terminated due to a terminated TCP worker")
//           context.stop(self)

//         case x: Terminated ⇒
//           log.error(workerDescription + " has been terminated due to a internal error")
//           context.stop(self)
//       }

//     behavior = default
//     behavior
//   }

//   def receive = disconnected
// }

// // private class DownStreamHandler[Cmd, Evt](init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef) extends Actor with ActorLogging with Stash {
// //   import SentinelClientWorker._
// //   import context.dispatcher

// //   context watch connection

// // }

// class WaitingSentinelClientWorker[Cmd, Evt](address: InetSocketAddress, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
//                                             workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends SentinelClientWorker(address, stages, workerDescription)(lowBytes, highBytes, maxBufferSize) {

//   var requestRunning = false
//   var requests = Queue[SentinelCommand[Evt]]()

//   override def connected(init: Init[WithinActorContext, Cmd, Evt], connection: ActorRef): Receive = {
//     val r: Receive = {
//       case x: init.Event ⇒
//         val actor = receiverQueue.head
//         receiverQueue = receiverQueue.tail
//         actor.forward(x)

//         requestRunning = false
//         if (requests.length > 0) connected(init, connection) {
//           val cmd = requests.head
//           requests = requests.tail
//           cmd
//         }

//       case o: Operation[Cmd, Evt] ⇒
//         requestRunning match {
//           case false ⇒
//             super.connected(init, connection)(o)
//             requestRunning = true
//           case true ⇒ requests :+= o
//         }

//       case o: UpStreamOperation[Cmd, Evt] ⇒
//         requestRunning match {
//           case false ⇒
//             super.connected(init, connection)(o)
//             requestRunning = true
//           case true ⇒ requests :+= o
//         }
//     }
//     r orElse super.connected(init, connection)
//   }
// }
