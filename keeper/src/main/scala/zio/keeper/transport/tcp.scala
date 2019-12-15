package zio.keeper.transport

import java.io.IOException
import java.math.BigInteger

import zio._
import zio.clock.Clock
import zio.duration._
import zio.keeper.TransportError
import zio.keeper.TransportError._
import zio.logging.Logging
import zio.macros.delegate._
import zio.nio._
import zio.nio.channels._

object tcp {

  trait Live extends Transport {
    self: Clock =>
    val connectionTimeout: Duration
    val requestTimeout: Duration

    val logger: Logging.Service[Any, String]

    val transport = new Transport.Service[Any] {

      override def connect(to: SocketAddress) =
        (for {
          socketChannelAndClose  <- AsynchronousSocketChannel().withEarlyRelease.mapError(ExceptionWrapper)
          (close, socketChannel) = socketChannelAndClose
          _ <- socketChannel
                .connect(to)
                .mapError(ExceptionWrapper)
                .timeoutFail(ConnectionTimeout(to, connectionTimeout))(connectionTimeout)
                .toManaged_
          _ <- logger.info("transport connected to " + to).toManaged_
        } yield new NioChannelOut(socketChannel, to, requestTimeout, close, self))
          .provide(self)

      override def bind(addr: SocketAddress)(connectionHandler: ChannelOut => UIO[Unit]) =
        AsynchronousServerSocketChannel()
          .flatMap(s => s.bind(addr).toManaged_.as(s))
          .mapError(BindFailed(addr, _))
          .withEarlyRelease
          .onExit { _ =>
            logger.info("shutting down server")
          }
          .mapM {
            case (close, server) =>
              (for {
                cur <- server.accept.withEarlyRelease
                        .mapError(ExceptionWrapper)
                        .mapM {
                          case (close, socket) =>
                            socket.remoteAddress.flatMap {
                              case None =>
                                // This is almost impossible here but still we need to handle it.
                                ZIO.fail(ExceptionWrapper(new RuntimeException("cannot obtain address")))
                              case Some(addr) =>
                                logger
                                  .info("connection accepted from: " + addr)
                                  .as(
                                    new NioChannelOut(socket, addr, connectionTimeout, close, self)
                                  )
                            }
                        }
                        .preallocate

                _ <- cur.use(connectionHandler).fork
              } yield ()).forever.fork
                .as(new NioChannelIn(server, close))
          }

    }

  }

  def withTcpTransport(
    connectionTimeout: Duration,
    sendTimeout: Duration
  ) =
    enrichWithM[Transport](tcpTransport(connectionTimeout, sendTimeout))

  def tcpTransport(
    connectionTimeout: Duration,
    requestTimeout: Duration
  ): ZIO[Clock with Logging[String], Nothing, Transport] = {
    val connectionTimeout_ = connectionTimeout
    val requestTimeout_    = requestTimeout
    ZIO.environment[Clock with Logging[String]].map { env =>
      new Live with Clock {
        override val connectionTimeout: Duration          = connectionTimeout_
        override val requestTimeout: Duration             = requestTimeout_
        override val logger: Logging.Service[Any, String] = env.logging
        override val clock: Clock.Service[Any]            = env.clock
      }
    }
  }

}

class NioChannelOut(
  socket: AsynchronousSocketChannel,
  remoteAddress: SocketAddress,
  requestTimeout: Duration,
  finalizer: URIO[Any, Any],
  clock: Clock
) extends ChannelOut {

  override def send(data: Chunk[Byte]): ZIO[Any, TransportError, Unit] = {
    val size = data.size
    (for {
      _ <- socket
            .write(Chunk((size >>> 24).toByte, (size >>> 16).toByte, (size >>> 8).toByte, size.toByte))
            .mapError(ExceptionWrapper(_))
      _ <- socket.write(data).retry(Schedule.recurs(3)).mapError(ExceptionWrapper(_))
    } yield ())
      .catchSome {
        case ExceptionWrapper(ex: IOException) if ex.getMessage == "Connection reset by peer" =>
          close *> ZIO.fail(ExceptionWrapper(ex))
      }
      .timeoutFail(RequestTimeout(remoteAddress, requestTimeout))(requestTimeout)
      .provide(clock)
  }

  override def read: ZIO[Any, TransportError, Chunk[Byte]] =
    (for {
      length <- socket
                 .read(4)
                 .flatMap(c => ZIO.effect(new BigInteger(c.toArray).intValue()))
                 .mapError(ExceptionWrapper)
      data <- socket.read(length).mapError(ExceptionWrapper)
    } yield data)
      .catchSome {
        case ExceptionWrapper(ex: IOException) if ex.getMessage == "Connection reset by peer" =>
          close *> ZIO.fail(ExceptionWrapper(ex))
      }

  override def isOpen: ZIO[Any, TransportError, Boolean] =
    socket.isOpen

  override def close: ZIO[Any, TransportError, Unit] =
    finalizer.ignore

}

class NioChannelIn(
  serverSocket: AsynchronousServerSocketChannel,
  finalizer: URIO[Any, Any]
) extends ChannelIn {

  override def isOpen: ZIO[Any, TransportError, Boolean] =
    serverSocket.isOpen

  override def close: ZIO[Any, TransportError, Unit] =
    finalizer.ignore

}
