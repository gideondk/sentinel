package nl.gideondk.sentinel

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }
import scalaz._
import scalaz.Scalaz._
import scalaz.stream._
import scalaz.effect.IO
import scala.concurrent._

import akka.actor._

import scala.concurrent.Future
import scalaz.contrib.std.scalaFuture._

final case class Task[A](get: IO[Future[Try[A]]]) { self ⇒
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

  def apply[A](a: ⇒ Future[A]): Task[A] = Task(Monad[IO].point(a.map(Try(_)) recover { case x ⇒ Try(throw x) }))

  def sequence[A](z: List[Task[A]]): Task[List[A]] =
    Task(z.map(_.get).sequence[IO, Future[Try[A]]].map(x ⇒ Future.fold(x)(scala.util.Try(List[A]()))((a, b) ⇒ a.flatMap(x ⇒ b.map(x ++ List(_))))))

  def sequenceSuccesses[A](z: List[Task[A]]): Task[List[A]] =
    Task(z.map(_.get).sequence[IO, Future[Try[A]]].map(x ⇒ Future.sequence(x).map(z ⇒ Try(z.filter(_.isSuccess).map(_.get)))))
}

trait TaskScalazConversions {
  implicit def taskToScalazTask[T](t: ⇒ Task[T]): scalaz.concurrent.Task[T] = {
    scalaz.concurrent.Task.async {
      register ⇒
        t.start.onComplete {
          case Success(Success(v))  ⇒ register(\/-(v))
          case Success(Failure(ex)) ⇒ register(-\/(ex))
          case Failure(ex)          ⇒ register(-\/(ex))
        }
    }
  }

  implicit def taskToScalazTaskNT(implicit ctx: ExecutionContext) = new NaturalTransformation[Task, scalaz.concurrent.Task] {
    def apply[A](fa: Task[A]): scalaz.concurrent.Task[A] = taskToScalazTask(fa)
  }

  implicit def scalazTaskToTaskNT(implicit ctx: ExecutionContext) = new NaturalTransformation[scalaz.concurrent.Task, Task] {
    def apply[A](fa: scalaz.concurrent.Task[A]): Task[A] = scalazTaskToTask(fa)
  }

  implicit def scalazTaskToTask[T](t: scalaz.concurrent.Task[T]): Task[T] = {
    val p: Promise[T] = Promise()
    Task {
      t.runAsync {
        case -\/(ex) ⇒ p.failure(ex)
        case \/-(r)  ⇒ p.success(r)
      }
      p.future
    }
  }

  implicit def scalazTaskProcessToTaskProcess[A](t: Process[scalaz.concurrent.Task, A]) = t.translate(scalazTaskToTaskNT)
  implicit def taskProcessToScalazTaskProcess[A](t: Process[Task, A]) = t.translate(taskToScalazTaskNT)
}

trait TaskImplementation extends TaskFunctions with TaskScalazConversions {
  implicit def taskMonadInstance = new TaskMonad {}
  implicit def taskComonadInstance(implicit d: Duration) = new TaskComonad {
    override protected val atMost = d
  }
  implicit def taskCatchableInstance = new TaskCatchable {}
}

trait TaskScalazStreamImplementation extends TaskImplementation {
  import Process._
  def actorResource[O](target: ActorRef)(acquire: ActorRef ⇒ Task[_])(release: ActorRef ⇒ Task[_])(step: ActorRef ⇒ Task[O]): Process[Task, O] = {
      def go(step: Task[O], onExit: Process[Task, O]): Process[Task, O] =
        await[Task, O, O](step)(
          o ⇒ emit(o) ++ go(step, onExit),
          onExit,
          onExit)
    await(acquire(target))(x ⇒ {
      val onExit = Process.suspend(eval(release(target)).drain)
      go(step(target), onExit)
    }, halt, halt)
  }
}

object Task extends TaskScalazStreamImplementation {
}

trait FutureCatchable extends Catchable[Future] {
  def fail[A](e: Throwable): Future[A] = Future.failed(e)
  def attempt[A](t: Future[A]): Future[Throwable \/ A] = Monad[Future].map(t)(x ⇒ \/-(x))
}

object CatchableFuture {
  implicit def futureCatchableInstance = new FutureCatchable {}
}