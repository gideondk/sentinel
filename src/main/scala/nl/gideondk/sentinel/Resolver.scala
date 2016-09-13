package nl.gideondk.sentinel

import akka.stream.scaladsl.{Flow, Source}
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import nl.gideondk.sentinel.ConsumerAction._

import scala.util.{Failure, Success, Try}


class ResponseStage[Evt, Cmd](resolver: Processor[Evt]) extends GraphStage[FanOutShape2[Evt, Cmd, Response[Evt]]] {

  private val events = Inlet[Evt]("EventIn")
  private val responses = Outlet[Response[Evt]]("ResponseOut")
  private val signals = Outlet[Cmd]("SignalOut")

  val shape = new FanOutShape2(events, responses, signals)

  override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var chunkSource: SubSourceOutlet[Evt] = _

    private def chunkSubStreamStarted = chunkSource != null

    private def idle = this

    def setInitialHandlers(): Unit = setHandlers(events, responses, idle)

    def startStream(): Unit = {
      chunkSource = new SubSourceOutlet[Evt]("ChunkSource")
      chunkSource.setHandler(substreamHandler)
      setHandler(events, substreamHandler)
      push(responses, StreamResponse(Source.fromGraph(chunkSource.source)))
    }

    def onPush(): Unit = {

      val evt = grab(events)
      resolver.process(evt) match {
        case AcceptSignal ⇒ push(responses, SingularResponse(evt))

        case AcceptError ⇒ push(responses, SingularErrorResponse(evt))

        case StartStream => startStream()

        case ConsumeStreamChunk => startStream()

        case ConsumeChunkAndEndStream => push(responses, StreamResponse(Source.single(evt)))

        case Ignore ⇒ ()
      }
    }

    def onPull(): Unit = {
      if (!chunkSubStreamStarted) pull(events)
    }

    private lazy val substreamHandler = new InHandler with OutHandler {
      def endStream(): Unit = {
        chunkSource.complete()
        chunkSource = null

        if (isAvailable(responses)) pull(events)
        setInitialHandlers()
      }

      override def onPush(): Unit = {
        val chunk = grab(events)

        resolver.process(chunk) match {
          case ConsumeStreamChunk => chunkSource.push(chunk)

          case EndStream => endStream()

          case ConsumeChunkAndEndStream => chunkSource.push(chunk); endStream()

          case Ignore ⇒ ()
        }
      }

      override def onPull(): Unit = pull(events)

      override def onUpstreamFinish(): Unit = {
        chunkSource.complete()
        completeStage()
      }

      override def onUpstreamFailure(reason: Throwable): Unit = {
        chunkSource.fail(reason)
        failStage(reason)
      }
    }
  }
}


trait Processor[In] {
  def process: PartialFunction[In, Action]
}
