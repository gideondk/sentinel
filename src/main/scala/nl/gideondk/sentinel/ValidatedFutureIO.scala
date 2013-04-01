package nl.gideondk.sentinel

import scalaz._
import Scalaz._
import scalaz.effect._
import scala.concurrent.duration._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

final case class ValidatedFuture[+A](run: Future[Validation[Throwable, A]])

object ValidatedFuture {
  implicit val validatedFuture = new Monad[ValidatedFuture] {
    def point[A](a: ⇒ A): ValidatedFuture[A] = ValidatedFuture(Future(a))

    override def map[A, B](fa: ValidatedFuture[A])(f: A ⇒ B): ValidatedFuture[B] =
      ValidatedFuture(fa.run.map(validation ⇒ validation map f))

    def bind[A, B](fa: ValidatedFuture[A])(f: A ⇒ ValidatedFuture[B]) =
      ValidatedFuture(fa.run.flatMap(validation ⇒ validation match {
        case Success(succ) ⇒ f(succ).run
        case Failure(fail) ⇒ Future(fail.failure)
    }))
  }

  def apply[T](a: ⇒ Future[T]): ValidatedFuture[T] = ValidatedFuture((a.map(_.success) recover {
    case t ⇒ t.failure
  }))
}

case class ValidatedFutureIO[+A](run: IO[ValidatedFuture[A]]) { self ⇒
  def fulFill(duration: Duration = 5 seconds): IO[Validation[Throwable, A]] = run.map(x ⇒ Await.result(x.run, duration))

  def unsafePerformIO: ValidatedFuture[A] = run.unsafePerformIO

  def unsafeFulFill: Validation[Throwable, A] = unsafeFulFill()

  def unsafeFulFill(duration: Duration = 5 seconds): Validation[Throwable, A] = fulFill(duration).unsafePerformIO
}

trait ValidatedFutureIOInstances {
  implicit val validatedFutureIO = new Monad[ValidatedFutureIO] {
    def point[A](a: ⇒ A): ValidatedFutureIO[A] = ValidatedFutureIO(a.point[ValidatedFuture].point[IO])

    override def map[A, B](fa: ValidatedFutureIO[A])(f: A ⇒ B): ValidatedFutureIO[B] =
      ValidatedFutureIO(fa.run.map(valFut ⇒ valFut map f))

    def bind[A, B](fa: ValidatedFutureIO[A])(f: A ⇒ ValidatedFutureIO[B]): ValidatedFutureIO[B] =
      ValidatedFutureIO(fa.run.flatMap(valFut ⇒ valFut.flatMap(f(_).run.unsafePerformIO).point[IO]))

    def unsafePerformIO[A](fa: ValidatedFutureIO[A]): ValidatedFuture[A] = fa.run.unsafePerformIO()
  }
}

trait ValidatedFutureIOFunctions {
  def apply[T](a: ⇒ Future[T]): ValidatedFutureIO[T] = ValidatedFutureIO(ValidatedFuture(a).point[IO])

  def sequence[T](z: List[ValidatedFutureIO[T]]): ValidatedFutureIO[List[T]] =
    ValidatedFutureIO(z.map(_.run).sequence.map(l ⇒ Future.sequence(l.map(_.run.map(_.toValidationNEL)))
      .map(_.toList.sequence[({ type l[a] = ValidationNEL[Throwable, a] })#l, T])).map(z ⇒ ValidatedFuture(z.map(y ⇒ (y.bimap(x ⇒ x.head, x ⇒ x))))))
}

trait ValidatedFutureIOAnyCast { 
  import shapeless._
  import Typeable._

  implicit class Castable(val vfi: ValidatedFutureIO[Any]) {
    def as[U](implicit castU : Typeable[U]) = 
      ValidatedFutureIO(vfi.run.map(z ⇒ ValidatedFuture(z.run.map(x ⇒ x.flatMap(_.cast[U].toSuccess(new Exception("Wrong type cast.")))))))
  }
}

object ValidatedFutureIO extends ValidatedFutureIOInstances with ValidatedFutureIOFunctions with ValidatedFutureIOAnyCast
