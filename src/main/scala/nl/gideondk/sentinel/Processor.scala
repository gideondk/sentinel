package nl.gideondk.sentinel

import akka.actor.{Actor, Props}
import akka.stream.BidiShape
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source}
import akka.util.ByteString
import akka.pattern.ask

//case object Processor {
//
//}
//
//class Processor[I, R <: Rx[I], O, T <: Tx[O]](rxProps: Props, txProps: Props, protocol: BidiFlow[I, ByteString, O, ByteString, Nothing], connection: Flow[ByteString, ByteString, _]) extends Actor {
//
//  val rx = Sink.actorSubscriber[O](rxProps)
//  val tx = Source.actorPublisher[I](txProps)
//
//  protocol >> connection
////  val flow = tx.via(connection).to(rx)
////  flow.run()
//
//  def receive = {
//    case x: I =>
//      tx. ? x
//  }
//}
//
//
///*
//
//
//--> Request --> Processor --> Tx (bp) --> TCP --> Rx (bp) --> Processor --> Response
//
//
//
//
// */