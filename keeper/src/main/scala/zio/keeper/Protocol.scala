package zio.keeper

import zio._
import zio.keeper.transport.Connection

trait Protocol[-R, +E, -I, +O, +A] { self =>

  def step(in: I): ZIO[R, E, (Chunk[O], Either[A, Protocol[R, E, I, O, A]])]

  def cont[R1 <: R, E1 >: E, I1 <: I, O1 >: O, A1 >: A](
    f: A => Option[Protocol[R1, E1, I1, O1, A1]]
  ): Protocol[R1, E1, I1, O1, A1] =
    new Protocol[R1, E1, I1, O1, A1] {

      def step(in: I1): ZIO[R1, E1, (Chunk[O1], Either[A1, Protocol[R1, E1, I1, O1, A1]])] =
        self.step(in).map {
          case (out, next) =>
            (
              out,
              next.fold(
                a => f(a).fold[Either[A1, Protocol[R1, E1, I1, O1, A1]]](Left(a))(Right(_)),
                p => Right(p.cont(f))
              )
            )
        }
    }

  def contM[R1 <: R, E1 >: E, I1 <: I, O1 >: O, A1 >: A](
    f: A => ZIO[R1, E1, Option[Protocol[R1, E1, I1, O1, A1]]]
  ): Protocol[R1, E1, I1, O1, A1] =
    new Protocol[R1, E1, I1, O1, A1] {

      def step(in: I1): ZIO[R1, E1, (Chunk[O1], Either[A1, Protocol[R1, E1, I1, O1, A1]])] =
        self.step(in).flatMap {
          case (out, next) =>
            next
              .fold(
                a => f(a).map(_.fold[Either[A1, Protocol[R1, E1, I1, O1, A1]]](Left(a))(Right(_))),
                p => ZIO.succeedNow(Right(p.contM(f)))
              )
              .map((out, _))
        }
    }

  def flatMap[R1 <: R, E1 >: E, I1 <: I, O1 >: O, A1](
    f: A => Protocol[R1, E1, I1, O1, A1]
  ): Protocol[R1, E1, I1, O1, A1] =
    new Protocol[R1, E1, I1, O1, A1] {

      def step(in: I1): ZIO[R1, E1, (Chunk[O1], Either[A1, Protocol[R1, E1, I1, O1, A1]])] =
        self.step(in).map {
          case (out, next) =>
            (out, next.fold(a => Right(f(a)), p => Right(p.flatMap(f))))
        }
    }

  def flatMapM[R1 <: R, E1 >: E, I1 <: I, O1 >: O, A1](
    f: A => ZIO[R1, E1, Protocol[R1, E1, I1, O1, A1]]
  ): Protocol[R1, E1, I1, O1, A1] =
    new Protocol[R1, E1, I1, O1, A1] {

      def step(in: I1): ZIO[R1, E1, (Chunk[O1], Either[A1, Protocol[R1, E1, I1, O1, A1]])] =
        self.step(in).flatMap {
          case (out, next) =>
            next.fold(a => f(a).map(Right(_)), p => ZIO.succeedNow(Right(p.flatMapM(f)))).map((out, _))
        }
    }

  def forever: Protocol[R, E, I, O, Nothing] =
    self.flatMap(_ => self.forever)

  def mapAll[I1, O1, A1](f: I1 => I, g: O => O1, h: A => A1): Protocol[R, E, I1, O1, A1] =
    new Protocol[R, E, I1, O1, A1] {

      def step(in: I1): ZIO[R, E, (Chunk[O1], Either[A1, Protocol[R, E, I1, O1, A1]])] =
        self.step(f(in)).map {
          case (out, next) =>
            (out.map(g), next.fold(a => Left(h(a)), proto => Right(proto.mapAll(f, g, h))))
        }
    }

  def mapAllM[R1 <: R, E1 >: E, I1, O1, A1](
    f: I1 => ZIO[R1, E1, I],
    g: O => ZIO[R1, E1, O1],
    h: A => ZIO[R1, E1, A1]
  ): Protocol[R1, E1, I1, O1, A1] =
    new Protocol[R1, E1, I1, O1, A1] {

      def step(in: I1): ZIO[R1, E1, (Chunk[O1], Either[A1, Protocol[R1, E1, I1, O1, A1]])] =
        f(in).flatMap {
          self.step(_).flatMap {
            case (out, next) =>
              for {
                out  <- out.mapM(g)
                next <- next.fold(a => h(a).map(Left(_)), proto => ZIO.succeedNow(Right(proto.mapAllM(f, g, h))))
              } yield (out, next)
          }
        }
    }

  def mapIn[I1](f: I1 => I): Protocol[R, E, I1, O, A] =
    new Protocol[R, E, I1, O, A] {

      def step(in: I1): ZIO[R, E, (Chunk[O], Either[A, Protocol[R, E, I1, O, A]])] =
        self.step(f(in)).map { case (out, next) => (out, next.map(_.mapIn(f))) }
    }

  def mapInM[R1 <: R, E1 >: E, I1](f: I1 => ZIO[R1, E1, I]): Protocol[R1, E1, I1, O, A] =
    new Protocol[R1, E1, I1, O, A] {

      def step(in: I1): ZIO[R1, E1, (Chunk[O], Either[A, Protocol[R1, E1, I1, O, A]])] =
        f(in).flatMap(self.step(_).map { case (out, next) => (out, next.map(_.mapInM(f))) })
    }

  def mapOut[O1](f: O => O1): Protocol[R, E, I, O1, A] =
    new Protocol[R, E, I, O1, A] {

      def step(in: I): ZIO[R, E, (Chunk[O1], Either[A, Protocol[R, E, I, O1, A]])] =
        self.step(in).map { case (out, next) => (out.map(f), next.map(_.mapOut(f))) }
    }

  def mapOutM[R1 <: R, E1 >: E, O1](f: O => ZIO[R1, E1, O1]): Protocol[R1, E1, I, O1, A] =
    new Protocol[R1, E1, I, O1, A] {

      def step(in: I): ZIO[R1, E1, (Chunk[O1], Either[A, Protocol[R1, E1, I, O1, A]])] =
        self.step(in).flatMap { case (out, next) => ZIO.foreach(out)(f).map((_, next.map(_.mapOutM(f)))) }
    }

  def mapResult[A1](f: A => A1): Protocol[R, E, I, O, A1] =
    new Protocol[R, E, I, O, A1] {

      def step(in: I): ZIO[R, E, (Chunk[O], Either[A1, Protocol[R, E, I, O, A1]])] =
        self.step(in).map {
          case (out, next) =>
            (out, next.fold(a => Left(f(a)), proto => Right(proto.mapResult(f))))
        }
    }

  def mapResultM[R1 <: R, E1 >: E, A1](f: A => ZIO[R1, E1, A1]): Protocol[R1, E1, I, O, A1] =
    new Protocol[R1, E1, I, O, A1] {

      def step(in: I): ZIO[R1, E1, (Chunk[O], Either[A1, Protocol[R1, E1, I, O, A1]])] =
        self.step(in).flatMap {
          case (out, next) =>
            next.fold(a => f(a).map(Left(_)), proto => ZIO.succeedNow(Right(proto.mapResultM(f)))).map((out, _))
        }
    }

  def onEnd[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, _]): Protocol[R1, E1, I, O, A] =
    mapResultM(a => f(a).as(a))

  lazy val unit: Protocol[R, E, I, O, Unit] =
    mapResult(_ => ())

}

object Protocol {

  val end: Protocol[Any, Nothing, Any, Nothing, Unit] =
    fromFunction(_ => (Chunk.empty, Left(())))

  def fromEffect[R, E, I, O, A](
    f: I => ZIO[R, E, (Chunk[O], Either[A, Protocol[R, E, I, O, A]])]
  ): Protocol[R, E, I, O, A] =
    new Protocol[R, E, I, O, A] {

      def step(in: I): ZIO[R, E, (Chunk[O], Either[A, Protocol[R, E, I, O, A]])] =
        f(in)

    }

  def fromFunction[R, E, I, O, A](f: I => (Chunk[O], Either[A, Protocol[R, E, I, O, A]])): Protocol[R, E, I, O, A] =
    new Protocol[R, E, I, O, A] {

      def step(in: I): ZIO[R, E, (Chunk[O], Either[A, Protocol[R, E, I, O, A]])] =
        ZIO.succeedNow(f(in))

    }

  def run[R, E, I, O, A](con: Connection[R, E, O, I], initial: Protocol[R, E, I, O, A]): ZIO[R, E, Option[A]] =
    con.receive
      .mapError[Either[E, A]](Left(_))
      .foldM(initial) {
        case (protocol, in) =>
          protocol.step(in).mapError(Left(_)).flatMap {
            case (out, next) =>
              ZIO.foreach(out)(con.send).mapError(Left(_)) *> next.fold(a => ZIO.fail(Right(a)), ZIO.succeedNow(_))
          }
      }
      .foldM(
        _.fold(ZIO.fail(_), a => ZIO.succeedNow(Some(a))),
        _ => ZIO.succeedNow(None)
      )

}
