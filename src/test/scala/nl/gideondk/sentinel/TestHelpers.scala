package nl.gideondk.sentinel

import org.scalatest.{ Suite, BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.ShouldMatchers

import akka.io.SymmetricPipelineStage
import akka.util.ByteString

import akka.actor._
import akka.testkit._

import java.util.concurrent.atomic.AtomicInteger

import protocols._

abstract class TestKitSpec extends TestKit(ActorSystem(java.util.UUID.randomUUID.toString))
    with Suite
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

    stringB.toString()
  }
}
