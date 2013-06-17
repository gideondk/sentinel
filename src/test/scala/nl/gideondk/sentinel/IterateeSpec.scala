package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import org.specs2.mutable.Specification

import akka.actor.ActorSystem
import akka.io.{ LengthFieldFrame, PipePair, PipelineContext, PipelineStage }
import akka.routing.RandomRouter
import akka.util.{ ByteString, ByteStringBuilder }
import client._
import nl.gideondk.sentinel.pipelines.EnumeratorStage
import play.api.libs.iteratee.{ Enumerator, Iteratee }
import scalaz.Scalaz._
import server.SentinelServer

case class Chunk(end: Boolean, chunk: ByteString)
case class TotalSize(length: Long)

class ChunkMessageUpStage extends PipelineStage[PipelineContext, Chunk, ByteString, TotalSize, ByteString] {
  override def apply(ctx: PipelineContext) = new PipePair[Chunk, ByteString, TotalSize, ByteString] {
    implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

    override val commandPipeline = { msg: Chunk ⇒
      val bsb = new ByteStringBuilder()
      bsb.putByte(if (msg.end) 1.toByte else 0.toByte)
      bsb ++= msg.chunk
      Seq(Right(bsb.result))
    }

    override val eventPipeline = { bs: ByteString ⇒
      val bi = bs.iterator
      Seq(Left(TotalSize(bi.getLong)))
    }
  }
}

class ChunkMessageDownStage extends PipelineStage[PipelineContext, TotalSize, ByteString, Chunk, ByteString] {
  override def apply(ctx: PipelineContext) = new PipePair[TotalSize, ByteString, Chunk, ByteString] {
    implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

    override val commandPipeline = { msg: TotalSize ⇒
      val bsb = new ByteStringBuilder()
      bsb.putLong(msg.length)
      Seq(Right(bsb.result))
    }

    override val eventPipeline = { bs: ByteString ⇒
      val bi = bs.iterator
      val lastByte = bi.getByte.toInt
      val lastChunk = if (lastByte.toInt == 0) false else true

      val rest = bi.toByteString
      Seq(Left(Chunk(lastChunk, rest)))
    }
  }
}

object ChunkedUploadServerHandler {
  def handle(events: Enumerator[Chunk]): Future[TotalSize] = {
    (events |>>> Iteratee.fold(0) { (t, c: Chunk) ⇒
      t + c.chunk.length
    }).map(TotalSize(_))
  }
}

trait ChunkUploadWorkers {
  implicit val system = ActorSystem("chunk-upload-system")

  def clientStages = new ChunkMessageUpStage >> new LengthFieldFrame(1024 * 1024 * 10)
  def serverStages = new EnumeratorStage(((x: Chunk) ⇒ x.end), false) >> new ChunkMessageDownStage >> new LengthFieldFrame(1024 * 1024 * 10)

  val server = SentinelServer.async(8004, ChunkedUploadServerHandler.handle, "Ping Server")(serverStages, maxBufferSize = 1024L * 1024L * 1024L)
  val client = SentinelClient("localhost", 8004, RandomRouter(4), "Ping Client")(clientStages, maxBufferSize = 1024L * 1024L * 1024L)
}

class ChunkUploadSpec extends Specification with ChunkUploadWorkers {
  sequential

  "A client" should {
    "retrieve items in correct sequence" in {
      implicit val duration = Duration(10, scala.concurrent.duration.SECONDS)

      val num = 50 * 10
      val connNum = 4

      val chunks = List.fill(num)(Chunk(false, LargerPayloadTestHelper.randomBSForSize((1024 * 1024 * 0.1).toInt).compact)) ++ List(Chunk(true, ByteString()))
      val res = Enumerator(chunks: _*) |~>>> client

      res.copoint.length == chunks.foldLeft(0)((t, c) ⇒ t + c.chunk.length)
    }
  }

  step {
    system.shutdown()
  }
}
