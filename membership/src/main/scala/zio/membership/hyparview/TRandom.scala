package zio.membership.hyparview

import zio._
import zio.stm._
import com.github.ghik.silencer.silent
import zio.random.Random

import scala.collection.immutable.{ Stream => ScStream }

object TRandom {

  /**
   * Service that allows to acesss randomness in a STM transaction.
   */
  trait Service {

    /**
     * Pick a random element from a list.
     */
    def selectOne[A](values: List[A]): STM[Nothing, Option[A]]

    /**
     * Pick `n` random elements from a list without replacement.
     */
    def selectN[A](values: List[A], n: Int): STM[Nothing, List[A]]
  }

  def using[R <: TRandom, E, A](f: Service => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.environment[TRandom].flatMap(env => f(env.get))

  def live: ZLayer[Random, Nothing, TRandom] = {
    @silent("deprecated")
    val makePickRandom: URIO[Random, Int => STM[Nothing, Int]] =
      for {
        seed    <- random.nextInt
        sRandom = new scala.util.Random(seed)
        ref     <- TRef.make(ScStream.continually((i: Int) => sRandom.nextInt(i))).commit
      } yield (i: Int) => ref.modify(s => (s.head(i), s.tail))

    ZLayer.fromEffect {
      makePickRandom.map { pickRandom =>
        new Service {
          def selectOne[A](values: List[A]): STM[Nothing, Option[A]] =
            if (values.isEmpty) STM.succeed(None)
            else {
              for {
                index    <- pickRandom(values.size)
                selected = values(index)
              } yield Some(selected)
            }

          def selectN[A](values: List[A], n: Int): STM[Nothing, List[A]] = {
            def go(remaining: Vector[A], toPick: Int, acc: List[A]): STM[Nothing, List[A]] =
              (remaining, toPick) match {
                case (Vector(), _) | (_, 0) => STM.succeed(acc)
                case _ =>
                  pickRandom(remaining.size).flatMap { index =>
                    val x  = remaining(index)
                    val xs = remaining.patch(index, Nil, 1)
                    go(xs, toPick - 1, x :: acc)
                  }
              }
            go(values.toVector, Math.max(0, n), Nil)
          }
        }
      }
    }
  }
}
