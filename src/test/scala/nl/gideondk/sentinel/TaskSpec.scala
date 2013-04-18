package nl.gideondk.sentinel

import Task._
import server._
import client._

import org.specs2.mutable.Specification

import akka.actor.IO.Chunk
import akka.actor.IO._
import akka.actor._

import akka.io._

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

import scala.annotation.tailrec
import scala.util.{ Try, Success, Failure }
import java.nio.ByteOrder

class TaskSpec extends Specification {
  implicit val timeout = Duration(10, SECONDS)

  "A Task" should {
    "be able to be run correctly" in {
      val task = Task(Future(1))
      task.copoint == 1
    }

    "be able to be sequenced correctly" in {
      val tasks = Task.sequence((for (i ← 0 to 9) yield i.point[Task]).toList)
      tasks.copoint.length == 10
    }

    "should short circuit in case of a sequenced failure" in {
      val s1 = 1.point[Task]
      val s2 = 2.point[Task]
      val f1 = Task(Future.failed(new Exception("")))

      val tasks = Task.sequence(List(s1, f1, s2))
      tasks.run.isFailure
    }

    "should only return successes when sequenced for successes" in {
      val s1 = 1.point[Task]
      val s2 = 2.point[Task]
      val f1 = Task(Future.failed(new Exception("")))

      val f: Task[Int] ⇒ String = ((t: Task[Int]) ⇒ t.copoint + "123")
      s1.cobind(f)

      val tasks = Task.sequenceSuccesses(List(s1, f1, s2))
      tasks.run.get.length == 2
    }

  }
}
