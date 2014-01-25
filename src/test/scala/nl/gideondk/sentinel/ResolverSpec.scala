package nl.gideondk.sentinel

// import scala.concurrent._
// import scala.concurrent.ExecutionContext.Implicits.global

// import scala.util.Try

// import org.scalatest.BeforeAndAfterAll
// import org.scalatest.WordSpec
// import org.scalatest.matchers.ShouldMatchers

// import akka.io.{ LengthFieldFrame, PipelineContext, SymmetricPipePair, SymmetricPipelineStage }
// import akka.routing.RoundRobinRouter
// import akka.util.ByteString

// import Task._
// import server._
// import client._

// import scalaz._
// import Scalaz._

// import scalaz.stream._

// import akka.actor._
// import akka.routing._
// import akka.testkit._
// import scala.concurrent.duration._
// import scala.concurrent._

// import protocols._

// import scalaz.contrib.std.scalaFuture.futureInstance

// import nl.gideondk.sentinel._
// import CatchableFuture._

// import java.net.InetSocketAddress

// class ResolverSpec extends WordSpec with ShouldMatchers {
//   import SimpleMessage._

//   implicit val duration = Duration(5, SECONDS)

//   "A client" should {
//     "be able to request a response from a server" in new TestKitSpec {
//       val portNumber = TestHelpers.portNumber.getAndIncrement()
//       val s = server(portNumber)
//       val c = client(portNumber)

//       val action = c <~< SimpleCommand(PING_PONG_COMMAND, "")
//       action.run.isSuccess
//     }

//     "be able to send a stream to a server" in new TestKitSpec {
//       val portNumber = TestHelpers.portNumber.getAndIncrement()
//       val s = server(portNumber)
//       val c = client(portNumber)

//       val count = 500
//       val chunks = List.fill(count)(SimpleStreamChunk("ABCDEF"))
//       val action = c <<?~~< (SimpleCommand(TOTAL_CHUNK_SIZE, ""), Process.emitRange(0, count) |> process1.lift(x ⇒ SimpleStreamChunk("ABCDEF")) onComplete (Process.emit(SimpleStreamChunk(""))))

//       val localLength = chunks.foldLeft(0)((b, a) ⇒ b + a.payload.length)
//       action.run.isSuccess && action.run.toOption.get.payload.toInt == localLength
//     }

//     "be able to receive streams from a server" in new TestKitSpec {
//       val portNumber = TestHelpers.portNumber.getAndIncrement()
//       val s = server(portNumber)
//       val c = client(portNumber)

//       val count = 500
//       val action = c <~~?>> SimpleCommand(GENERATE_NUMBERS, count.toString)

//       val stream = action.copoint
//       val result = Await.result(stream.chunkAll.runLastOr(throw new Exception("Problems occured during stream handling")), 5 seconds)
//       result.length == count
//     }
//   }
// }
