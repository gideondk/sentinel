package nl.gideondk.sentinel

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.testkit._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.language.postfixOps

abstract class SentinelSpec(_system: ActorSystem)
    extends TestKit(_system) with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {

  implicit val patience = PatienceConfig(testKitSettings.DefaultTimeout.duration, Span(500, org.scalatest.time.Millis))
  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true
  implicit val ec = _system.dispatcher

  val log: LoggingAdapter = Logging(system, this.getClass)

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
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

    while ((stringB.length + paddingString.length) < size) stringB.append(paddingString)

    stringB.toString()
  }
}
