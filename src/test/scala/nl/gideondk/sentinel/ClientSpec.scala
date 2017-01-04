package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import nl.gideondk.sentinel.client.{ Client, ClientStage, Host }
import nl.gideondk.sentinel.protocol._

import scala.concurrent.Promise
import scala.util.Try

class ClientSpec extends SentinelSpec(ActorSystem()) {
  "a Client" should {
    "keep message order intact" in {
      val port = TestHelpers.portNumber.incrementAndGet()
      val server = ClientStageSpec.mockServer(system, port)
      implicit val materializer = ActorMaterializer()

      val numberOfMessages = 100

      val messages = (for (i ← 0 to numberOfMessages) yield (SingularCommand[SimpleMessageFormat](SimpleReply(i.toString)))).toList
      val sink = Sink.foreach[(Try[Event[SimpleMessageFormat]], Promise[Event[SimpleMessageFormat]])] { case (event, context) ⇒ context.complete(event) }

      val client = Client.flow(Source.single(ClientStage.HostUp(Host("localhost", port))), SimpleHandler, false, SimpleMessage.protocol)
      val results = Source(messages).via(client).runWith(Sink.seq)

      whenReady(results) { result ⇒
        result should equal(messages.map(x ⇒ SingularEvent(x.payload)))
      }
    }
  }
}