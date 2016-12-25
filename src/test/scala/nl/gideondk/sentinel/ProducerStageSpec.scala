package nl.gideondk.sentinel

import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import nl.gideondk.sentinel.pipeline.ProducerStage
import nl.gideondk.sentinel.protocol.{SimpleMessageFormat, SimpleReply, SingularCommand, StreamingCommand}

import scala.concurrent._
import scala.concurrent.duration._

object ProducerStageSpec {
  def stage() = new ProducerStage[SimpleMessageFormat, SimpleMessageFormat]()
}

class ProducerStageSpec extends AkkaSpec {

  import ProducerStageSpec._

  "The ProducerStage" should {
    "handle outgoing messages" in {
      implicit val materializer = ActorMaterializer()

      val command = SingularCommand[SimpleMessageFormat](SimpleReply("A"))
      val result = Await.result(Source(List(command)).via(stage()).runWith(Sink.seq), 5 seconds)

      result shouldBe Vector(SimpleReply("A"))

      val multiResult = Await.result(Source(List(command, command, command)).via(stage()).runWith(Sink.seq), 5 seconds)
      multiResult shouldBe Vector(SimpleReply("A"), SimpleReply("A"), SimpleReply("A"))
    }

    "handle outgoing streams" in {
      implicit val materializer = ActorMaterializer()

      val items = List(SimpleReply("A"), SimpleReply("B"), SimpleReply("C"), SimpleReply("D"))
      val command = StreamingCommand[SimpleMessageFormat](Source(items))

      val result = Await.result(Source(List(command)).via(stage()).runWith(Sink.seq), 5 seconds)
      result shouldBe items

      val multiResult = Await.result(Source(List(command, command, command)).via(stage()).runWith(Sink.seq), 5 seconds)
      multiResult shouldBe (items ++ items ++ items)
    }
  }
}