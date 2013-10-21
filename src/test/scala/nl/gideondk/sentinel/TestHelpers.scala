package nl.gideondk.sentinel

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.util.Try

import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import akka.io.{ LengthFieldFrame, PipelineContext, SymmetricPipePair, SymmetricPipelineStage }
import akka.routing.RoundRobinRouter
import akka.util.ByteString

import Task._
import server._
import client._

import scalaz._
import Scalaz._

import akka.actor._
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent._

import java.util.concurrent.atomic.AtomicInteger

import protocols._

import java.net.InetSocketAddress

abstract class TestKitSpec extends TestKit(ActorSystem())
    with WordSpec
    with ShouldMatchers
    with BeforeAndAfterAll
    with ImplicitSender {
  override def afterAll = {
    system.shutdown()
  }
}

object TestHelpers {
  val portNumber = new AtomicInteger(10500)
}

object BenchmarkHelpers {
  def timed(desc: String, n: Int)(benchmark: ⇒ Unit) = {
    println("* " + desc)
    val t = System.currentTimeMillis
    benchmark
    val d = System.currentTimeMillis - t

    println("* - number of ops/s: " + n / (d / 1000.0) + "\n")
  }

  def throughput(desc: String, size: Double, n: Int)(benchmark: ⇒ Unit) = {
    println("* " + desc)
    val t = System.currentTimeMillis
    benchmark
    val d = System.currentTimeMillis - t

    val totalSize = n * size
    println("* - number of mb/s: " + totalSize / (d / 1000.0) + "\n")
  }
}

object LargerPayloadTestHelper {
  def randomBSForSize(size: Int) = {
    implicit val be = java.nio.ByteOrder.BIG_ENDIAN
    val stringB = new StringBuilder(size)
    val paddingString = "abcdefghijklmnopqrs"

    while (stringB.length() + paddingString.length() < size) stringB.append(paddingString)

    ByteString(stringB.toString().getBytes())
  }
}