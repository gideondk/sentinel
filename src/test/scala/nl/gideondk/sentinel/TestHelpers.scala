package nl.gideondk.sentinel

import org.scalatest.{ Suite, BeforeAndAfterAll, WordSpec }

import akka.util.ByteString

import akka.actor._
import akka.testkit._

import java.util.concurrent.atomic.AtomicInteger

import org.scalactic.Constraint

import language.postfixOps
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }

import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.{ Config, ConfigFactory }
import akka.dispatch.Dispatchers
import akka.testkit.TestEvent._
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        loggers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
                                                    """)

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*AkkaSpec.?$|.*StreamSpec.?$)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}


abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpecLike with Matchers with BeforeAndAfterAll 
  with ConversionCheckedTripleEquals with ScalaFutures {

  implicit val patience = PatienceConfig(testKitSettings.DefaultTimeout.duration, Span(100, org.scalatest.time.Millis))

  implicit val ec = _system.dispatcher

  def this(config: Config) = this(ActorSystem(
    AkkaSpec.getCallerName(getClass),
    ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    beforeTermination()
    shutdown()
    afterTermination()
  }

  protected def atStartup() {}

  protected def beforeTermination() {}

  protected def afterTermination() {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))

  def expectedTestDuration: FiniteDuration = 60 seconds

  def muteDeadLetters(messageClasses: Class[_]*)(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled) {
      def mute(clazz: Class[_]): Unit =
        sys.eventStream.publish(Mute(DeadLettersFilter(clazz)(occurrences = Int.MaxValue)))
      if (messageClasses.isEmpty) mute(classOf[AnyRef])
      else messageClasses foreach mute
    }

  // for ScalaTest === compare of Class objects
  implicit def classEqualityConstraint[A, B]: Constraint[Class[A], Class[B]] =
    new Constraint[Class[A], Class[B]] {
      def areEqual(a: Class[A], b: Class[B]) = a == b
    }

  implicit def setEqualityConstraint[A, T <: Set[_ <: A]]: Constraint[Set[A], T] =
    new Constraint[Set[A], T] {
      def areEqual(a: Set[A], b: T) = a == b
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
