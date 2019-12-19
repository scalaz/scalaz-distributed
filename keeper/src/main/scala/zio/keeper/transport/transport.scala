package zio.keeper.transport

import zio.keeper.TransportError
import zio.nio.SocketAddress
import zio.{Chunk, UIO, ZIO, ZManaged}

trait Transport {
  val transport: Transport.Service[Any]
}

object Transport {

  trait Service[R] {
    def connect(to: SocketAddress): ZManaged[R, TransportError, ChannelOut]

    def bind(localAddr: SocketAddress)(
      connectionHandler: ChannelOut => UIO[Unit]
    ): ZManaged[R, TransportError, ChannelIn]
  }

}

sealed trait Channel {

  //conv
  val isOpen: ZIO[Any, TransportError, Boolean]

  //conv
  def close: ZIO[Any, TransportError, Unit]
}

trait ChannelOut extends Channel {

  def send(data: Chunk[Byte]): ZIO[Any, TransportError, Unit]

  //conv
  def read: ZIO[Any, TransportError, Chunk[Byte]]
}

trait ChannelIn extends Channel {}
