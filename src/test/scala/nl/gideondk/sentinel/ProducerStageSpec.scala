package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import nl.gideondk.sentinel.pipeline.ProducerStage
import nl.gideondk.sentinel.protocol.{ SimpleMessageFormat, SimpleReply, SingularCommand, StreamingCommand }

import scala.concurrent._
import scala.concurrent.duration._

object ProducerStageSpec {
  val stage = new ProducerStage[SimpleMessageFormat, SimpleMessageFormat]()
}

class ProducerStageSpec extends SentinelSpec(ActorSystem()) {

  import ProducerStageSpec._

  "The ProducerStage" should {
    "handle outgoing messages" in {
      implicit val materializer = ActorMaterializer()

      val command = SingularCommand[SimpleMessageFormat](SimpleReply("A"))
      val singularResult = Source(List(command)).via(stage).runWith(Sink.seq)

      Await.result(singularResult, 5 seconds) should equal(Seq(SimpleReply("A")))

      //      val multiResult = Source(List(command, command, command)).via(stage).runWith(Sink.seq)
      //      whenReady(multiResult) { result ⇒
      //        result should equal(Seq(SimpleReply("A"), SimpleReply("A"), SimpleReply("A")))
      //      }
    }

    //    "handle outgoing streams" in {
    //      implicit val materializer = ActorMaterializer()
    //
    //      val items = List(SimpleReply("A"), SimpleReply("B"), SimpleReply("C"), SimpleReply("D"))
    //      val command = StreamingCommand[SimpleMessageFormat](Source(items))
    //
    //      val singularResult = Source(List(command)).via(stage).runWith(Sink.seq)
    //      whenReady(singularResult) { result ⇒
    //        result should equal(items)
    //      }
    //
    //      val multiResult = Source(List(command)).via(stage).runWith(Sink.seq)
    //      whenReady(multiResult) { result ⇒
    //        result should equal(items ++ items ++ items)
    //      }
    //    }
  }
}