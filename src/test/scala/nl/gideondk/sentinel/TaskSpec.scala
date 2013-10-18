package nl.gideondk.sentinel

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, SECONDS }

import org.scalatest._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers

import Task.taskComonadInstance
import scalaz.Scalaz._

class TaskSpec extends WordSpec with ShouldMatchers {
  implicit val timeout = Duration(10, SECONDS)

  "A Task" should {
    "be able to be run correctly" in {
      val task = Task(Future(1))
      task.copoint should equal(1)
    }

    "be able to be sequenced correctly" in {
      val tasks = Task.sequence((for (i ← 0 to 9) yield i.point[Task]).toList)
      tasks.copoint.length should equal(10)
    }

    "should short circuit in case of a sequenced failure" in {
      val s1 = 1.point[Task]
      val s2 = 2.point[Task]
      val f1: Task[Int] = Task(Future.failed(new Exception("")))

      val tasks = Task.sequence(List(s1, f1, s2))
      tasks.run.isFailure
    }

    "should only return successes when sequenced for successes" in {
      val s1 = 1.point[Task]
      val s2 = 2.point[Task]
      val f1: Task[Int] = Task(Future.failed(new Exception("")))

      val f: Task[Int] ⇒ String = ((t: Task[Int]) ⇒ t.copoint + "123")
      s1.cobind(f)

      val tasks = Task.sequenceSuccesses(List(s1, f1, s2))
      tasks.run.get.length == 2
    }
  }
}
