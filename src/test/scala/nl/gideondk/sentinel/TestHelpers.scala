package nl.gideondk.sentinel

<<<<<<< HEAD
import org.scalatest.{ Suite, BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.ShouldMatchers

import akka.io.SymmetricPipelineStage
import akka.util.ByteString
=======
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
>>>>>>> develop

import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.testkit._
<<<<<<< HEAD
=======
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
>>>>>>> develop

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import scala.language.postfixOps

abstract class SentinelSpec(_system: ActorSystem)
    extends TestKit(_system) with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {

<<<<<<< HEAD
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
=======
  implicit val patience = PatienceConfig(testKitSettings.DefaultTimeout.duration, Span(1500, org.scalatest.time.Millis))
  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true
  implicit val ec = _system.dispatcher

  val log: LoggingAdapter = Logging(system, this.getClass)
>>>>>>> develop

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  def benchmark[A](f: Future[A], numberOfItems: Int, waitFor: Duration = Duration(10, TimeUnit.SECONDS)): Unit = {
    val t = System.currentTimeMillis
    Await.result(f, waitFor)
    val d = System.currentTimeMillis - t
    println("Number of ops/s: " + numberOfItems / (d / 1000.0) + "\n")
  }
}

<<<<<<< HEAD
object LargerPayloadTestHelper {
  def randomBSForSize(size: Int) = {
    implicit val be = java.nio.ByteOrder.BIG_ENDIAN
    val stringB = new StringBuilder(size)
    val paddingString = "abcdefghijklmnopqrs"

    while (stringB.length() + paddingString.length() < size) stringB.append(paddingString)

    stringB.toString()
  }
}
=======
object TestHelpers {
  val portNumber = new AtomicInteger(10500)
}
>>>>>>> develop
