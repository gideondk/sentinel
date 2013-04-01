package nl.gideondk.sentinel

import ValidatedFutureIO._
import server._
import client._

import org.specs2.mutable.Specification

import akka.actor.IO.Chunk
import akka.actor.IO._
import akka.actor._

import akka.actor.{ IO ⇒ LIO }

import java.util.Date

import scalaz._
import Scalaz._
import effect._

import concurrent.Await
import concurrent.duration.Duration

import akka.util.{ ByteStringBuilder, ByteString }
import akka.routing.RandomRouter

import scala.concurrent.ExecutionContext.Implicits.global

import concurrent._
import concurrent.duration._

case class Header(name: String, value: String)
case class Response(statusCode: Int, statusMessage: String, httpver: String, headers: List[Header], body: Option[String])

object HTTPConstants {
  val SP = ByteString(" ")
  val HT = ByteString("\t")
  val CRLF = ByteString("\r\n")
  val COLON = ByteString(":")
  val PERCENT = ByteString("%")
  val PATH = ByteString("/")
  val QUERY = ByteString("?")
}

object HTTPIteratees {
  import HTTPConstants._

  def readResponse =
    for {
      statusLine ← readStatusLine
      (httpver, statusCode, statusMessage) = statusLine
      headers ← readHeaders
      body ← readBody(headers)
    } yield Response(statusCode, statusMessage, httpver, headers, body.map(x ⇒ new String(x.toArray)))

  def readHeaders = {
      def step(found: List[Header]): LIO.Iteratee[List[Header]] = {
        LIO peek 2 flatMap {
          case CRLF ⇒ LIO takeUntil CRLF flatMap (_ ⇒ LIO Done found)
          case _    ⇒ readHeader flatMap (header ⇒ step(header :: found))
        }
      }
    step(Nil)
  }

  def ascii(bytes: ByteString): String = bytes.decodeString("US-ASCII").trim

  def readHeader =
    for {
      name ← LIO takeUntil COLON
      value ← LIO takeUntil CRLF flatMap readMultiLineValue
    } yield Header(ascii(name), ascii(value))

  def readMultiLineValue(initial: ByteString): LIO.Iteratee[ByteString] = LIO peek 1 flatMap {
    case SP ⇒ LIO takeUntil CRLF flatMap (bytes ⇒ readMultiLineValue(initial ++ bytes))
    case _  ⇒ LIO Done initial
  }

  def readBody(headers: List[Header]) =
    if (headers.exists(header ⇒ header.name == "Content-Length")) {
      val length = headers.find(header ⇒ header.name == "Content-Length").first.get.value.toInt
      LIO.take(length) map (Some(_))
    }
    else
      LIO Done None

  def readStatusLine =
    for {
      httpVersion ← LIO takeUntil SP
      statusCode ← (LIO takeUntil SP).map(x ⇒ ascii(x).toInt)
      statusMessage ← LIO takeUntil CRLF
    } yield (ascii(httpVersion), statusCode, ascii(statusMessage))
}

class HTTPClientWorker extends SentinelClientWorker {
  import akka.io._
  import akka.io.Tcp._

  val writeAck = false
  val workerDescription = "HTTP Client Worker"
  val processRequest = HTTPIteratees.readResponse

  override def messageHandler = {
    case PeerClosed ⇒
      log.debug(workerDescription+" disconnected from ("+address+")")
  }
}

/* Silly HTTP client, Sentinel should work best in socket persistant environment. 
   Implemented here to show possible diversities */

object HTTPClientTestHelper {
  implicit val actorSystem = ActorSystem("test-system")

  def GET(host: String, path: String) = {
    val httpClient = SentinelClient.randomRouting[HTTPClientWorker](host, 80, 1, "HTTP Client")
    val request = ByteString("GET "+path+" HTTP/1.1\n"+
      "host: "+host+"\n\n")
    httpClient ?? request
  }
}

class ClientSpec extends Specification {
  "A http request" should {
    "be able to retrieve content" in {
      val req = HTTPClientTestHelper.GET("lab050.com", "/")
      val result = req.as[Response].unsafeFulFill
      result.toOption.get.body.isDefined
    }
  }
}
