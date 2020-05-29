package zio.keeper.swim

import zio.duration.Duration
import zio.keeper.NodeAddress
import zio.keeper.swim.Message.{ NoResponse, WithTimeout }
import zio.{ IO, ZIO, keeper }

sealed trait Message[+A] {
  self =>

  final def transformM[B](fn: A => IO[keeper.Error, B]): IO[keeper.Error, Message[B]] =
    self match {
      case msg: Message.Direct[A] =>
        fn(msg.message).map(b => msg.copy(message = b))
      case msg: Message.Broadcast[A] =>
        fn(msg.message).map(b => msg.copy(message = b))
      case msg: Message.Batch[A] =>
        for {
          m1   <- msg.first.transformM(fn)
          m2   <- msg.second.transformM(fn)
          rest <- ZIO.foreach(msg.rest)(_.transformM(fn))
        } yield Message.Batch(m1, m2, rest: _*)
      case msg: WithTimeout[A] =>
        msg.message
          .transformM(fn)
          .map(b => msg.copy(message = b, action = msg.action.flatMap(_.transformM(fn))))
      case NoResponse =>
        Message.noResponse
    }
}

object Message {

  final case class Direct[A](node: NodeAddress, conversationId: Long, message: A) extends Message[A]

  final case class Batch[A](first: Message[A], second: Message[A], rest: Message[A]*) extends Message[A]

  final case class Broadcast[A](message: A) extends Message[A]

  final case class WithTimeout[A](message: Message[A], action: IO[keeper.Error, Message[A]], timeout: Duration)
      extends Message[A]
  case object NoResponse extends Message[Nothing]

  def direct[A](node: NodeAddress, message: A): ZIO[ConversationId, Nothing, Direct[A]] =
    ConversationId.next.map(Direct(node, _, message))

  def withTimeout[R, A](message: Message[A], action: ZIO[R, keeper.Error, Message[A]], timeout: Duration) =
    for {
      env <- ZIO.environment[R]
    } yield WithTimeout(message, action.provide(env), timeout)

  def withTimeoutM[R, R1, A](
    message: ZIO[R1, keeper.Error, Message[A]],
    action: ZIO[R, keeper.Error, Message[A]],
    timeout: Duration
  ) =
    for {
      env <- ZIO.environment[R]
      msg <- message
    } yield WithTimeout(msg, action.provide(env), timeout)

  val noResponse = ZIO.succeedNow(NoResponse)

}
