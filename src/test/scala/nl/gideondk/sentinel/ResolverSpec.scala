package nl.gideondk.sentinel

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

import scalaz._
import Scalaz._

import akka.actor._
import akka.routing._
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent._

import play.api.libs.iteratee._

import protocols._

import java.net.InetSocketAddress

class ResolverSpec extends WordSpec with ShouldMatchers {
  import SimpleMessage._

  implicit val duration = Duration(5, SECONDS)

  "A client" should {
    "be able to request a response from a server" in {
      true
    }
  }
}