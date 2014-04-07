package nl.gideondk.sentinel

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try

import scalaz._
import scalaz.Scalaz._
import scalaz.effect.IO

final case class Task[A](get: IO[Future[A]]) {
  self ⇒
  def start: Future[A] = get.unsafePerformIO

  def run(implicit atMost: Duration): Try[A] = Await.result((start.map(Try(_)) recover {
    case x ⇒ Try(throw x)
  }), atMost)

  def recover[U >: A](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext) = get.map(_.recover(pf))

  def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext) = get.map(_.recoverWith(pf))
}

trait TaskMonad extends Monad[Task] {
  def point[A](a: ⇒ A): Task[A] = Task((Future(a)).point[IO])

  def bind[A, B](fa: Task[A])(f: A ⇒ Task[B]) =
    Task(Monad[IO].point(fa.get.unsafePerformIO.flatMap {
      x ⇒
        f(x).get.unsafePerformIO
    }))
}

trait TaskCatchable extends Catchable[Task] with TaskMonad {
  def fail[A](e: Throwable): Task[A] = Task(Future.failed(e))

  def attempt[A](t: Task[A]): Task[Throwable \/ A] = map(t)(x ⇒ \/-(x))
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

  def apply[A](a: ⇒ Future[A]): Task[A] = Task(Monad[IO].point(a))

  def sequence[A](z: List[Task[A]]): Task[List[A]] =
    Task(z.map(_.get).sequence[IO, Future[A]].map(x ⇒ Future.sequence(x)))

  def sequenceSuccesses[A](z: List[Task[A]]): Task[List[A]] =
    Task(z.map(_.get).sequence[IO, Future[A]].map {
      x ⇒
        Future.sequence(x.map(f ⇒ f.map(Some(_)) recover {
          case x ⇒ None
        })).map(_.filter(_.isDefined).map(_.get))
    })

}

trait TaskImplementation extends TaskFunctions {
  implicit def taskMonadInstance = new TaskMonad {}

  implicit def taskComonadInstance(implicit d: Duration) = new TaskComonad {
    override protected val atMost = d
  }
}

object Task extends TaskImplementation {
}
