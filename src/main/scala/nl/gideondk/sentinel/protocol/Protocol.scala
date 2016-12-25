package nl.gideondk.sentinel.protocol

import akka.stream.scaladsl.BidiFlow
import akka.stream.{ BidiShape, Graph }

import scala.concurrent.Promise

case class RequestContext[Cmd, Evt](request: Cmd, responsePromise: Promise[Evt])

object Protocol {

  implicit class ProtocolChaining[IT, OT, IB, OB, Mat](bf: BidiFlow[IT, OT, IB, OB, Mat]) {
    def >>[NextOT, NextIB, Mat2](bidi: Graph[BidiShape[OT, NextOT, NextIB, IB], Mat2]) = bf.atop(bidi)
  }

}