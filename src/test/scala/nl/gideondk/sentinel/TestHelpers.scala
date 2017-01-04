package nl.gideondk.sentinel

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.testkit._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import scala.language.postfixOps

abstract class SentinelSpec(_system: ActorSystem)
    extends TestKit(_system) with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {

  implicit val patience = PatienceConfig(testKitSettings.DefaultTimeout.duration, Span(1500, org.scalatest.time.Millis))
  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true
  implicit val ec = _system.dispatcher

  val log: LoggingAdapter = Logging(system, this.getClass)

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

object TestHelpers {
  val portNumber = new AtomicInteger(10500)
}