package nl.gideondk.sentinel.pipeline

import akka.stream._
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import nl.gideondk.sentinel.protocol.ConsumerAction._
import nl.gideondk.sentinel.protocol._

class ConsumerStage[Evt, Cmd](resolver: Resolver[Evt]) extends GraphStage[FanOutShape2[Evt, (Evt, ProducerAction[Evt, Cmd]), Event[Evt]]] {
  private val eventIn = Inlet[Evt]("ConsumerStage.Event.In")
  private val actionOut = Outlet[(Evt, ProducerAction[Evt, Cmd])]("ConsumerStage.Action.Out")
  private val signalOut = Outlet[Event[Evt]]("ConsumerStage.Signal.Out")

  val shape = new FanOutShape2(eventIn, actionOut, signalOut)

  override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var chunkSource: SubSourceOutlet[Evt] = _

    private def chunkSubStreamStarted = chunkSource != null

    private def idle = this

    def setInitialHandlers(): Unit = setHandlers(eventIn, signalOut, idle)

    /*
    *
    * Substream Logic
    *
    * */

    val pullThroughHandler = new OutHandler {
      override def onPull() = {
        pull(eventIn)
      }
    }

    val substreamHandler = new InHandler with OutHandler {
      def endStream(): Unit = {
        chunkSource.complete()
        chunkSource = null

        if (isAvailable(signalOut) && !hasBeenPulled(eventIn)) pull(eventIn)
        setInitialHandlers()
      }

      override def onPush(): Unit = {
        val chunk = grab(eventIn)
        resolver.process(chunk) match {
          case ConsumeStreamChunk ⇒
            chunkSource.push(chunk)

          case EndStream ⇒
            endStream()

          case ConsumeChunkAndEndStream ⇒
            chunkSource.push(chunk)
            endStream()

          case Ignore ⇒ ()
        }
      }

      override def onPull(): Unit = {
        // TODO: Recheck internal flow; checking should be obsolete
        if (!hasBeenPulled(eventIn)) pull(eventIn)
      }

      override def onUpstreamFinish(): Unit = {
        chunkSource.complete()
        completeStage()
      }

      override def onUpstreamFailure(reason: Throwable): Unit = {
        chunkSource.fail(reason)
        failStage(reason)
      }
    }

    def startStream(initialChunk: Option[Evt]): Unit = {
      chunkSource = new SubSourceOutlet[Evt]("ConsumerStage.Event.In.ChunkSubStream")
      chunkSource.setHandler(pullThroughHandler)
      setHandler(eventIn, substreamHandler)
      setHandler(signalOut, substreamHandler)

      initialChunk match {
        case Some(x) ⇒ push(signalOut, StreamEvent(Source.single(x) ++ Source.fromGraph(chunkSource.source)))
        case None    ⇒ push(signalOut, StreamEvent(Source.fromGraph(chunkSource.source)))
      }
    }

    def consumeStream(initialChunk: Evt): Unit = {
      //      emit(actionOut, (initialChunk, ProducerAction.ConsumeStream(Source.fromGraph(chunkSource.source))))
    }

    def onPush(): Unit = {
      val evt = grab(eventIn)

      resolver.process(evt) match {
        case x: ProducerAction.Signal[Evt, Cmd] ⇒ emit(actionOut, (evt, x))

        //        case x: ProducerAction.ProduceStream[Evt, Cmd] ⇒ emit(actionOut, (evt, x))

        case AcceptSignal                       ⇒ push(signalOut, SingularEvent(evt))

        case AcceptError                        ⇒ push(signalOut, SingularErrorEvent(evt))

        case StartStream                        ⇒ startStream(None)

        case ConsumeStreamChunk                 ⇒ startStream(Some(evt))

        case ConsumeChunkAndEndStream           ⇒ push(signalOut, StreamEvent(Source.single(evt)))

        case Ignore                             ⇒ ()
      }
    }

    def onPull(): Unit = {
      if (!chunkSubStreamStarted && !hasBeenPulled(eventIn)) pull(eventIn)
    }

    setHandler(actionOut, this)

    setInitialHandlers()
  }
}