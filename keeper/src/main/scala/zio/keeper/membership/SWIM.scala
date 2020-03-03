package zio.keeper.membership

import java.util.UUID

import zio._
import zio.clock.Clock
import zio.duration._
import zio.keeper.ClusterError._
import zio.keeper.Message.{ readMessage, serializeMessage }
import zio.keeper.{ Message, _ }
import zio.keeper.discovery.Discovery
import zio.keeper.protocol.InternalProtocol
import zio.keeper.protocol.InternalProtocol._
import zio.keeper.transport.{ ChannelOut, Transport }
import zio.logging.Logging
import zio.logging.slf4j._
import zio.macros.delegate._
import zio.nio.core.{ InetAddress, SocketAddress }
import zio.random.Random
import zio.stm.{ STM, TMap }
import zio.stream.{ Stream, ZStream }

import scala.collection.immutable.SortedSet

final class SWIM(
  localMember_ : Member,
  nodeChannels: Ref[Map[NodeId, ChannelOut]],
  gossipStateRef: Ref[GossipState],
  userMessageQueue: zio.Queue[Message],
  clusterMessageQueue: zio.Queue[Message],
  clusterEventsQueue: zio.Queue[MembershipEvent],
  subscribeToBroadcast: UIO[Stream[Nothing, Chunk[Byte]]],
  publishToBroadcast: Chunk[Byte] => UIO[Unit],
  msgOffset: Ref[Long],
  acks: TMap[Long, Promise[Error, Unit]]
) extends Membership.Service[Any] {

  override val events: ZStream[Any, Error, MembershipEvent] =
    ZStream.fromQueue(clusterEventsQueue)

  override val localMember: ZIO[Any, Nothing, Member] =
    ZIO.succeed(localMember_)

  override def broadcast(data: Chunk[Byte]): IO[Error, Unit] =
    serializeMessage(UUID.randomUUID(), localMember_, data, 2).flatMap[Any, Error, Unit](publishToBroadcast).unit

  override def nodes: ZIO[Any, Nothing, List[NodeId]] =
    nodeChannels.get
      .map(_.keys.toList)

  override def receive: Stream[Error, Message] =
    zio.stream.Stream.fromQueue(userMessageQueue)

  override def send(data: Chunk[Byte], receipt: NodeId): IO[Error, Unit] =
    sendMessage(receipt, 2, data)

  private def sendMessage(to: NodeId, msgType: Int, payload: Chunk[Byte]) =
    for {
      node <- nodeChannels.get.map(_.get(to))
      _ <- node match {
            case Some(channel) =>
              serializeMessage(UUID.randomUUID(), localMember_, payload, msgType) >>= channel.send
            case None => ZIO.fail(UnknownNode(to))
          }
    } yield ()

  private def acceptConnectionRequests =
    for {
      env          <- ZManaged.environment[Logging[String] with Transport with Clock]
      _            <- handleClusterMessages(ZStream.fromQueue(clusterMessageQueue)).fork.toManaged_
      localAddress <- localMember.flatMap(_.addr.socketAddress).toManaged_
      server <- transport.bind(localAddress) { channelOut =>
                 (for {
                   state <- gossipStateRef.get
                   _     <- sendInternalMessage(channelOut, UUID.randomUUID(), NewConnection(state, localMember_))
                   _ <- expects(channelOut) {
                         case JoinCluster(remoteState, remoteMember) =>
                           logger.info(remoteMember.toString + " joined cluster") *>
                             addMember(remoteMember, channelOut) *>
                             updateState(remoteState) *>
                             listenOnChannel(channelOut, remoteMember)
                       }
                 } yield ())
                   .catchAll(ex => logger.error(s"Connection failed: $ex"))
                   .provide(env)
               }
    } yield server

  private def ack(id: Long) =
    for {
      _ <- logger.info(s"message ack $id")
      promOpt <- acks
                  .get(id)
                  .flatMap(
                    _.fold(STM.succeed[Option[Promise[Error, Unit]]](None))(prom => acks.delete(id).as(Some(prom)))
                  )
                  .commit
      _ <- promOpt.fold(ZIO.unit)(_.succeed(()).unit)
    } yield ()

  private def addMember(member: Member, send: ChannelOut) =
    gossipStateRef.update(_.addMember(member)) *>
      nodeChannels.update(_ + (member.nodeId -> send)) *>
      propagateEvent(MembershipEvent.Join(member)) *>
      logger.info("add member: " + member)

  private def connect(
    addr: SocketAddress
  ) =
    for {
      connectionInit <- Promise.make[Error, (Member, ChannelOut)]
      _ <- transport
            .connect(addr)
            .use { channel =>
              logger.info(s"Initiating handshake with node at ${addr}") *>
                expects(channel) {
                  case NewConnection(remoteState, remoteMember) =>
                    (for {
                      state    <- gossipStateRef.get
                      newState = state.merge(remoteState)
                      _        <- addMember(remoteMember, channel)
                      _        <- updateState(newState)
                    } yield ()) *> connectionInit.succeed((remoteMember, channel)) *> listenOnChannel(
                      channel,
                      remoteMember
                    )
                }
            }
            .mapError(HandshakeError(addr, _))
            .catchAll(
              ex =>
                connectionInit.fail(ex) *>
                  logger.error(s"Failed initiating connection with node [ ${addr} ]: $ex")
            )
            .fork
      _ <- connectionInit.await
    } yield ()

  private def connectToSeeds(seeds: Set[SocketAddress]) =
    for {
      _            <- ZIO.foreach(seeds)(seed => connect(seed).ignore)
      currentNodes <- nodeChannels.get
      currentState <- gossipStateRef.get
      _ <- ZIO.foreach(currentNodes.values)(
            channel => sendInternalMessage(channel, UUID.randomUUID(), JoinCluster(currentState, localMember_))
          )
    } yield ()

  private def expects[R, A](
    channel: ChannelOut
  )(pf: PartialFunction[InternalProtocol, ZIO[R, Error, A]]): ZIO[R, Error, A] =
    for {
      bytes  <- readMessage(channel)
      msg    <- InternalProtocol.deserialize(bytes._2.payload)
      result <- pf.lift(msg).getOrElse(ZIO.fail(UnexpectedMessage(bytes._2)))
    } yield result

  private def handleClusterMessages(stream: Stream[Nothing, Message]) =
    stream.tap { message =>
      (for {
        payload <- InternalProtocol.deserialize(message.payload)
        _       <- logger.info(s"receive message: $payload")
        _ <- payload match {
              case Ack(ackId, state) =>
                updateState(state) *>
                  ack(ackId)
              case Ping(ackId, state) =>
                for {
                  _     <- updateState(state)
                  state <- gossipStateRef.get
                  _     <- sendInternalMessage(message.sender, message.id, Ack(ackId, state))
                } yield ()
              case PingReq(target, originalAckId, state) =>
                for {
                  _     <- updateState(state)
                  state <- gossipStateRef.get
                  _ <- sendInternalMessageWithAck(target.nodeId, 5.seconds)(ackId => Ping(ackId, state))
                        .foldM(
                          _ => ZIO.unit,
                          _ =>
                            gossipStateRef.get
                              .flatMap(
                                state => sendInternalMessage(message.sender, message.id, Ack(originalAckId, state))
                              )
                        )
                        .fork
                } yield ()
              case _ => logger.error("unknown message: " + payload)
            }
      } yield ())
        .catchAll(
          ex =>
            //we should probably reconnect to the sender.
            logger.error(s"Exception $ex processing cluster message $message")
        )
    }.runDrain

  private def listenOnChannel(
    channel: ChannelOut,
    partner: Member
  ): ZIO[Transport with Logging[String] with Clock, Error, Unit] = {

    def handleSends(messages: Stream[Nothing, Chunk[Byte]]) =
      messages.tap { bytes =>
        channel
          .send(bytes)
          .catchAll(ex => ZIO.fail(SendError(partner.nodeId, bytes, ex)))
      }.runDrain

    (for {
      _           <- logger.info(s"Setting up connection with [ ${partner.nodeId} ]")
      broadcasted <- subscribeToBroadcast
      _           <- handleSends(broadcasted).fork
      _           <- routeMessages(channel, clusterMessageQueue, userMessageQueue)
    } yield ())
  }

  private def routeMessages(
    channel: ChannelOut,
    clusterMessageQueue: Queue[Message],
    userMessageQueue: Queue[Message]
  ) = {
    val loop = readMessage(channel)
      .flatMap {
        case (msgType, msg) =>
          if (msgType == 1) {
            clusterMessageQueue.offer(msg).unit
          } else if (msgType == 2) {
            userMessageQueue.offer(msg).unit
          } else {
            //this should be dead letter
            logger.error("unsupported message type")
          }
      }
      .catchAll { ex =>
        logger.error(s"read message error: $ex")
      }
    loop.repeat(Schedule.doWhileM(_ => channel.isOpen.catchAll[Any, Nothing, Boolean](_ => ZIO.succeed(false))))
  }

  private def runSwim =
    Ref.make(0).flatMap { roundRobinOffset =>
      val loop = gossipStateRef.get.map(_.members.filterNot(_ == localMember_).toIndexedSeq).flatMap { nodes =>
        if (nodes.nonEmpty) {
          for {
            next   <- roundRobinOffset.update(old => if (old < nodes.size - 1) old + 1 else 0)
            state  <- gossipStateRef.get
            target = nodes(next) // todo: insert in random position and keep going in round robin version
            _ <- sendInternalMessageWithAck(target.nodeId, 10.seconds)(ackId => Ping(ackId, state))
                  .foldM(
                    _ => // attempt req messages
                    {
                      val nodesWithoutTarget = nodes.filter(_ != target)
                      for {
                        _ <- propagateEvent(MembershipEvent.Unreachable(target))
                        jumps <- ZIO.collectAll(
                                  List.fill(Math.min(3, nodesWithoutTarget.size))(
                                    zio.random.nextInt(nodesWithoutTarget.size).map(nodesWithoutTarget(_))
                                  )
                                )
                        pingReqs = jumps.map { jump =>
                          sendInternalMessageWithAck(jump.nodeId, 5.seconds)(
                            ackId => PingReq(target, ackId, state)
                          )
                        }

                        _ <- if (pingReqs.nonEmpty) {
                              ZIO
                                .raceAll(pingReqs.head, pingReqs.tail)
                                .foldM(
                                  _ => removeMember(target),
                                  _ =>
                                    logger.info(
                                      s"Successful ping req to [ ${target.nodeId} ] through [ ${jumps.map(_.nodeId).mkString(", ")} ]"
                                    )
                                )
                            } else {
                              logger.info(s"Ack failed timeout") *>
                                removeMember(target)
                            }
                      } yield ()
                    },
                    _ => logger.info(s"Successful ping to [ ${target.nodeId} ]")
                  )
          } yield ()
        } else {
          logger.info("No nodes to spread gossip to")
        }
      }
      loop.repeat(Schedule.spaced(10.seconds))
    }

  private def removeMember(member: Member) =
    gossipStateRef.update(_.removeMember(member)) *>
      nodeChannels.modify(old => (old.get(member.nodeId), old - member.nodeId)).flatMap {
        case Some(channel) =>
          channel.close.ignore *>
            logger.info("channel closed for member: " + member)
        case None =>
          ZIO.unit
      } *>
      propagateEvent(MembershipEvent.Leave(member)) *>
      logger.info("remove member: " + member)

  private def propagateEvent(event: MembershipEvent) =
    clusterEventsQueue.offer(event)

  private def sendInternalMessageWithAck(to: NodeId, timeout: Duration)(fn: Long => InternalProtocol) =
    for {
      offset <- msgOffset.update(_ + 1)
      prom   <- Promise.make[Error, Unit]
      _      <- acks.put(offset, prom).commit
      msg    = fn(offset)
      _      <- sendInternalMessage(to, UUID.randomUUID, fn(offset))
      _ <- prom.await
            .ensuring(acks.delete(offset).commit)
            .timeoutFail(AckMessageFail(offset, msg, to))(timeout)
    } yield ()

  private def sendInternalMessage(
    to: NodeId,
    correlationId: UUID,
    msg: InternalProtocol
  ): ZIO[Logging[String], Error, Unit] =
    for {
      node <- nodeChannels.get.map(_.get(to))
      _ <- node match {
            case Some(channel) =>
              sendInternalMessage(channel, correlationId, msg)
            case None => ZIO.fail(UnknownNode(to))
          }
    } yield ()

  private def sendInternalMessage(
    to: ChannelOut,
    correlationId: UUID,
    msg: InternalProtocol
  ): ZIO[Logging[String], Error, Unit] = {
    for {
      _       <- logger.info(s"sending $msg")
      payload <- msg.serialize
      msg     <- serializeMessage(correlationId, localMember_, payload, 1)
      _       <- to.send(msg)
    } yield ()
  }.catchAll { ex =>
    logger.info(s"error during sending message: $ex")
  }

  private def updateState(newState: GossipState): ZIO[Transport with Logging[String] with Clock, Error, Unit] =
    for {
      current <- gossipStateRef.get
      diff    = newState.diff(current)
      _       <- ZIO.foreach(diff.local)(n => (n.addr.socketAddress >>= connect).ignore)
    } yield ()

}

object SWIM {

  def withSWIM(port: Int) =
    enrichWithManaged[Membership](join(port))

  def join(
    port: Int
  ): ZManaged[Logging[String] with Clock with Random with Transport with Discovery, Error, Membership] =
    for {
      localHost            <- InetAddress.localHost.toManaged_.orDie
      localMember          = Member(NodeId.generateNew, NodeAddress(localHost.address, port))
      _                    <- logger.info(s"Starting node [ ${localMember.nodeId} ]").toManaged_
      nodes                <- zio.Ref.make(Map.empty[NodeId, ChannelOut]).toManaged_
      seeds                <- discovery.discoverNodes.toManaged_
      _                    <- logger.info("seeds: " + seeds).toManaged_
      userMessagesQueue    <- ZManaged.make(zio.Queue.bounded[Message](1000))(_.shutdown)
      clusterEventsQueue   <- ZManaged.make(zio.Queue.sliding[MembershipEvent](100))(_.shutdown)
      clusterMessagesQueue <- ZManaged.make(zio.Queue.bounded[Message](1000))(_.shutdown)
      gossipState          <- Ref.make(GossipState(SortedSet(localMember))).toManaged_
      broadcastQueue       <- ZManaged.make(zio.Queue.bounded[Chunk[Byte]](1000))(_.shutdown)
      subscriberBroadcast <- ZStream
                              .fromQueue(broadcastQueue)
                              .distributedWithDynamic[Nothing, Chunk[Byte]](
                                32,
                                _ => ZIO.succeed(_ => true),
                                _ => ZIO.unit
                              )
                              .map(_.map(_._2))
                              .map(_.map(ZStream.fromQueue(_).unTake))
      msgOffSet <- Ref.make(0L).toManaged_
      ackMap    <- TMap.empty[Long, Promise[Error, Unit]].commit.toManaged_

      swimMembership = new SWIM(
        localMember_ = localMember,
        nodeChannels = nodes,
        gossipStateRef = gossipState,
        userMessageQueue = userMessagesQueue,
        clusterMessageQueue = clusterMessagesQueue,
        clusterEventsQueue = clusterEventsQueue,
        subscribeToBroadcast = subscriberBroadcast,
        publishToBroadcast = (c: Chunk[Byte]) => broadcastQueue.offer(c).unit,
        msgOffset = msgOffSet,
        acks = ackMap
      )

      _ <- logger.info("Connecting to seed nodes: " + seeds).toManaged_
      _ <- swimMembership.connectToSeeds(seeds).toManaged_
      _ <- logger.info("Beginning to accept connections").toManaged_
      _ <- swimMembership.acceptConnectionRequests
            .use(channel => ZIO.never.ensuring(channel.close.ignore))
            .toManaged_
            .fork
      _ <- logger.info("Starting SWIM membership protocol").toManaged_
      _ <- swimMembership.runSwim.fork.toManaged_
    } yield new Membership {
      override def membership: Membership.Service[Any] = swimMembership
    }

}
