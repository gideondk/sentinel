package nl.gideondk.sentinel

import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Source}
import akka.stream.stage.GraphStageLogic._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import nl.gideondk.sentinel.ConsumerAction._

class ProducerStage[In, Out] extends GraphStage[FlowShape[Command[Out], Out]] {
  private val in = Inlet[Command[Out]]("ProducerStage.Command.In")
  private val out = Outlet[Out]("ProducerStage.Command.Out")

  var streaming = false
  var closeAfterCompletion = false

  val shape = new FlowShape(in, out)

  override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
    val defaultInHandler = new InHandler {
      override def onPush(): Unit = grab(in) match {
        case x: SingularCommand[Out] ⇒ push(out, x.payload)
        case x: StreamingCommand[Out] => stream(x.stream)
      }

      override def onUpstreamFinish(): Unit = {
        if (streaming) closeAfterCompletion = true
        else completeStage()
      }
    }

    val waitForDemandHandler = new OutHandler {
      def onPull(): Unit = pull(in)
    }

    setHandler(in, defaultInHandler)
    setHandler(out, waitForDemandHandler)

    def stream(outStream: Source[Out, Any]): Unit = {
      streaming = true
      val sinkIn = new SubSinkInlet[Out]("RenderingSink")
      sinkIn.setHandler(new InHandler {
        override def onPush(): Unit = push(out, sinkIn.grab())

        override def onUpstreamFinish(): Unit = {
          if (closeAfterCompletion) {
            completeStage()
          }
          else {
            streaming = false
            setHandler(out, waitForDemandHandler)
            if (isAvailable(out)) pull(in)
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = sinkIn.pull()

        override def onDownstreamFinish(): Unit = {
          completeStage()
          sinkIn.cancel()
        }
      })

      sinkIn.pull()
      outStream.runWith(sinkIn.sink)(subFusingMaterializer)
    }

  }
}
