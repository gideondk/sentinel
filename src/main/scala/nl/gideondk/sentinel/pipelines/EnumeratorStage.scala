package nl.gideondk.sentinel.pipelines

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

import akka.actor.{ Actor, ActorSystem, Props, Stash, actorRef2Scala }
import akka.io.{ PipePair, PipelineContext, PipelineStage }
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.iteratee.Enumerator
import scalaz.Scalaz._

class EnumeratorStage[Cmd <: AnyRef, Evt <: AnyRef](terminator: Evt ⇒ Boolean, includeTerminator: Boolean = false)(implicit system: ActorSystem, exc: ExecutionContext, timeout: FiniteDuration = 10 seconds) extends PipelineStage[PipelineContext, Cmd, Cmd, Enumerator[Evt], Evt] {
  case class PushChunk(c: Evt)
  case class NextChunk(identifier: String)

  class ChannelManager[A] extends Actor with Stash {
    case class Channel(hook: Option[Promise[Option[Evt]]] = None,
                       queue: scala.collection.mutable.Queue[Promise[Option[Evt]]] = scala.collection.mutable.Queue[Promise[Option[Evt]]]())

    var channels = Map[String, Channel]()
    var channelOrder = List[String]()

    def receive = {
      case NextChunk(identifier) ⇒
        channels.get(identifier) match {
          case Some(c) ⇒
            def addHookForIdentifier = {
                val p = Promise[Option[Evt]]()
                channels = channels ++ Map(identifier -> c.copy(hook = Some(p)))
                p
              }

            val chunk = {
              if (c.queue.length == 0) addHookForIdentifier
              else c.queue.dequeue()
            }

            sender ! chunk

          case None ⇒
            val startPromise = Promise[Option[Evt]]()
            channels = channels ++ Map(identifier -> Channel(Some(startPromise)))
            channelOrder = channelOrder ++ List(identifier)
            sender ! startPromise
            unstashAll()
        }

      case PushChunk(chunk) ⇒
        channelOrder.headOption >>= ((s: String) ⇒ channels.get(s)) match {
          case None ⇒
            stash()
          case Some(c) ⇒
            val promise = if (c.hook.isDefined) {
              channels = channels ++ Map(channelOrder.head -> c.copy(hook = None))
              c.hook.get
            } else {
              val p = Promise[Option[Evt]]()
              c.queue.enqueue(p)
              p
            }
            if (terminator(chunk)) {
              if (includeTerminator) {
                promise success Some(chunk)
                val p = Promise[Option[Evt]]() success None
                c.queue.enqueue(p)
              } else {
                promise success None
              }
              channelOrder = channelOrder diff List(channelOrder.head)
            } else {
              promise success Some(chunk)
            }
        }
    }

  }
  override def apply(ctx: PipelineContext) = new PipePair[Cmd, Cmd, Enumerator[Evt], Evt] {
    val channelManager = system.actorOf(Props(new ChannelManager).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
    var initial = true

    def nextChunk(identifier: String): Future[Option[Evt]] = {
      implicit val t = Timeout(timeout)
      (channelManager ? NextChunk(identifier)).mapTo[Promise[Option[Evt]]].flatMap(_.future)
    }

    def newEnum = {
      val identifier = java.util.UUID.randomUUID.toString
      Enumerator.generateM(nextChunk(identifier))
    }

    override val commandPipeline = { cmd: Cmd ⇒
      ctx.singleCommand(cmd)
    }

    override val eventPipeline = { evt: Evt ⇒
      if (initial) {
        initial = false
        val enum = newEnum
        channelManager ! PushChunk(evt)
        ctx.singleEvent(enum)
      } else if (terminator(evt)) {
        channelManager ! PushChunk(evt)
        initial = true
        ctx.nothing
      } else {
        channelManager ! PushChunk(evt)
        ctx.nothing
      }
    }
  }
}

