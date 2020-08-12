package zio.keeper.transport.testing

import zio._
import zio.stream.Take
import zio.stream.ZStream
import zio.test.{ AssertResult, Assertion }
import zio.keeper.transport.Connection

object MockConnection {

  sealed trait Script[+E, -I, +O] { self =>
    import Script._

    def ++ [E1 >: E, I1 <: I, O1 >: O](that: Script[E1, I1, O1]): Script[E1, I1, O1] =
      self.andThen(that)

    def <|> [E1, I1 <: I, O1 >: O](that: Script[E1, I1, O1]): Script[E1, I1, O1] =
      Or(self, that)

    def andThen[E1 >: E, I1 <: I, O1 >: O](that: Script[E1, I1, O1]): Script[E1, I1, O1] =
      AndThen(self, that)

    def repeat(n: Int): Script[E, I, O] = {
      def go(n: Int, acc: Script[E, I, O]): Script[E, I, O] =
        if (n <= 0) acc
        else (go(n - 1, acc ++ self))
      go(n, self)
    }

    def runEmits: (Chunk[O], Either[E, Option[Script[E, I, O]]]) =
      self match {
        case AndThen(first, second) =>
          val (out1, next1) = first.runEmits
          next1.fold(
            e => (out1, Left(e)),
            _.fold {
              val (out2, next2) = second.runEmits
              (out1 ++ out2, next2)
            } { next =>
              (out1, Right(Some(next ++ second)))
            }
          )
        case Or(first, second) =>
          val (out1, result1) = first.runEmits
          result1.fold(
            _ => {
              val (out2, result2) = second.runEmits
              (out1 ++ out2, result2)
            },
            _.fold[(Chunk[O], Either[E, Option[Script[E, I, O]]])]((out1, Right(None)))(
              next => (out1, Right(Some(next <|> second)))
            )
          )
        case EmitChunk(values) => (values, Right(None))
        case Fail(value)       => (Chunk.empty, Left(value))
        case script            => (Chunk.empty, Right(Some(script)))
      }

    def runOneAwait(in: I): Either[E, Option[Script[E, I, O]]] =
      self match {
        case AndThen(first, second) =>
          first.runOneAwait(in).right.map(_.fold(Some(second))(remaining => Some(remaining ++ second)))
        case Or(first, second) =>
          first.runOneAwait(in).fold(_ => second.runOneAwait(in), remaining => Right(remaining.map(_ <|> second)))
        case Await(assertion) =>
          assertion(in).fold[Either[E, None.type]](Right(None))(Left(_))
        case Fail(value) =>
          Left(value)
        case script =>
          Right(Some(script))
      }

    // run (runEmits, runOneAwait, runEmits) and compose results
    def run(in: I): (Chunk[O], Either[E, Option[Script[E, I, O]]]) = {
      val (out1, result) = runEmits
      result.fold(
        e => (out1, Left(e)),
        _.fold[(Chunk[O], Either[E, Option[Script[E, I, O]]])]((out1, Right(None))) { next1 =>
          next1
            .runOneAwait(in)
            .fold(
              e => (out1, Left(e)),
              _.fold[(Chunk[O], Either[E, Option[Script[E, I, O]]])]((out1, Right(None))) { next2 =>
                val (out2, result2) = next2.runEmits
                result2.fold(
                  e => (out1 ++ out2, Left(e)),
                  script => (out1 ++ out2, Right(script))
                )
              }
            )
        }
      )
    }
  }

  object Script {
    final case class AndThen[E, I, O](first: Script[E, I, O], second: Script[E, I, O]) extends Script[E, I, O]
    final case class Or[E, I, O](first: Script[_, I, O], second: Script[E, I, O])      extends Script[E, I, O]
    final case class Await[E, I](assertion: I => Option[E])                            extends Script[E, I, Nothing]
    final case class EmitChunk[O](values: Chunk[O])                                    extends Script[Nothing, Any, O]
    final case class Fail[E](value: E)                                                 extends Script[E, Any, Nothing]

    def await[I](assertion: Assertion[I]): Script[AssertResult, I, Nothing] = {
      def f = (in: I) => {
        val result = assertion.run(in)
        if (result.isSuccess) None
        else Some(result)
      }
      Await(f)
    }

    val awaitFail: Script[AssertResult, Any, Nothing] =
      await(Assertion.nothing)

    def emit[O](value: O): Script[Nothing, Any, O] =
      emitChunk(Chunk.single(value))

    def emitAll[O](values: O*): Script[Nothing, Any, O] =
      emitChunk(Chunk.fromIterable(values))

    def emitChunk[O](values: Chunk[O]): Script[Nothing, Any, O] =
      EmitChunk(values)

    val fail: Script[AssertResult, Any, Nothing] =
      Fail(Assertion.nothing.run(()))

  }

  def make[E, I, O](script: Script[E, I, O]): Managed[Nothing, Connection[Any, E, I, O]] =
    for {
      outbound <- Queue.unbounded[Take[E, O]].toManaged(_.shutdown)
      initial <- {
        val (out, result) = script.runEmits
        outbound.offer(Take.chunk(out)) *>
          result.fold(
            e => outbound.offer(Take.fail(e)).as(None),
            _.fold[UIO[Option[Script[E, I, O]]]](outbound.offer(Take.end).as(None))(next => ZIO.succeedNow(Some(next)))
          )
      }.toManaged_
      stateRef <- Ref.makeManaged(initial)
    } yield new Connection[Any, E, I, O] {

      def send(data: I): ZIO[Any, E, Unit] =
        stateRef
          .modify {
            case None => ((Chunk.empty, Right(None)), None)
            case Some(script) =>
              val (out, result) = script.run(data)
              ((out, result), result.right.toOption.flatten)
          }
          .flatMap {
            case (out, result) =>
              outbound.offer(Take.chunk(out)) *>
                result.fold(
                  e => outbound.offer(Take.fail(e)) *> ZIO.fail(e),
                  _.fold(outbound.offer(Take.end).unit)(_ => ZIO.unit)
                )
          }

      val receive: ZStream[Any, E, O] =
        ZStream.repeatEffectChunkOption(outbound.take.flatMap(_.done))

      val close: UIO[Unit] =
        ZIO.unit

    }
}
