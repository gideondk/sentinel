package nl.gideondk.sentinel

import scala.concurrent.duration._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

import scalaz._
import Scalaz._
import effect._
import scala.util.{ Success, Failure }

final case class Task[+A](get: IO[Future[Try[A]]]) { self ⇒
  def start: Future[Try[A]] = get.unsafePerformIO

  def run(implicit atMost: Duration): Try[A] = Await.result(start, atMost)
}

trait TaskMonad extends Monad[Task] {
  def point[A](a: ⇒ A): Task[A] = Task((Future(Try(a))).point[IO])

  def bind[A, B](fa: Task[A])(f: A ⇒ Task[B]) =
    Task(Monad[IO].point(fa.get.unsafePerformIO.flatMap {
      case Success(s) ⇒ f(s).get.unsafePerformIO
      case Failure(e) ⇒ Future(Failure(e))
    }))
}

trait TaskComonad extends Comonad[Task] with TaskMonad {
  implicit protected def atMost: Duration

  def cobind[A, B](fa: Task[A])(f: Task[A] ⇒ B): Task[B] = point(f(fa))

  def cojoin[A](a: Task[A]): Task[Task[A]] = point(a)

  def copoint[A](fa: Task[A]): A = fa.run.get
}

trait TaskFunctions {
  import scalaz._
  import Scalaz._

  def apply[T](a: ⇒ Future[T]): Task[T] = Task(Monad[IO].point(a.map(scala.util.Success(_)) recover { case x ⇒ scala.util.Failure(x) }))

  def sequence[T](z: List[Task[T]]): Task[List[T]] =
    Task(z.map(_.get).sequence.map(x ⇒ Future.fold(x)(scala.util.Try(List[T]()))((a, b) ⇒ a.flatMap(x ⇒ b.map(x ++ List(_))))))

  def sequenceSuccesses[T](z: List[Task[T]]): Task[List[T]] =
    Task(z.map(_.get).sequence.map(x ⇒ Future.sequence(x).map(z ⇒ Try(z.filter(_.isSuccess).map(_.get)))))
}

trait TaskImplementation extends TaskFunctions {
  implicit def taskMonadInstance = new TaskMonad {}
  implicit def taskComonadInstance(implicit d: Duration) = new TaskComonad {
    override protected val atMost = d
  }
}

object Task extends TaskImplementation