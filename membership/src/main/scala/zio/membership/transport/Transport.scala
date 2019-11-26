package zio.membership.transport

import zio._
import zio.nio._
import zio.stream._
import zio.membership.SendError
import zio.membership.ReceiveError

trait Transport {
  val transport: Transport.Service[Any]
}

object Transport {

  /**
   * Our low level transport interface that allows sending messages.
   * Also allows listening to messages sends from other nodes.
   */
  trait Service[R] {
    def send(to: SocketAddress, data: Chunk[Byte]): ZIO[R, SendError, Unit]
    def bind(addr: SocketAddress): ZStream[R, ReceiveError, Chunk[Byte]]
  }
}