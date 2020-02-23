package zio.keeper.membership.swim.protocols

import upickle.default._
import zio.ZIO
import zio.keeper.discovery.Discovery
import zio.keeper.membership.NodeAddress
import zio.keeper.membership.swim.Nodes.NodeState
import zio.keeper.membership.swim.{NodeId, Nodes, Protocol}
import zio.keeper.{ByteCodec, TaggedCodec}
import zio.logging.Logging
import zio.logging.slf4j._
import zio.stream.ZStream

sealed trait Initial

object Initial {

  implicit def taggedRequests(
    implicit
    c4: ByteCodec[Join],
    c6: ByteCodec[Accept.type],
    c7: ByteCodec[Reject]
  ): TaggedCodec[Initial] =
    TaggedCodec.instance(
      {
        case _: Join   => 13
        case Accept    => 15
        case _: Reject => 16
      }, {
        case 13 => c4.asInstanceOf[ByteCodec[Initial]]
        case 15 => c6.asInstanceOf[ByteCodec[Initial]]
        case 16 => c7.asInstanceOf[ByteCodec[Initial]]
      }
    )

  final case class Join(nodeAddress: NodeAddress) extends Initial

  implicit val codecJoin: ByteCodec[Join] =
    ByteCodec.fromReadWriter(macroRW[Join])

  case object Accept extends Initial

  implicit val codecAccept: ByteCodec[Accept.type] =
    ByteCodec.fromReadWriter(macroRW[Accept.type])

  case class Reject(msg: String) extends Initial

  object Reject {

    implicit val codec: ByteCodec[Reject] =
      ByteCodec.fromReadWriter(macroRW[Reject])
  }

  def protocol(nodes: Nodes) =
    ZIO.accessM[Discovery with Logging[String]](
      env =>
        Protocol[NodeId, Initial].apply(
          {
            case (sender, Join(_)) =>
              nodes
                .changeNodeState(sender, NodeState.Healthy)
                .as(Some((sender, Accept)))
            case (sender, Accept) =>
              nodes
                .changeNodeState(sender, NodeState.Healthy)
                .as(None)
            case (sender, Reject(msg)) =>
              logger.error("Rejected from cluster: " + msg) *>
                nodes.disconnect(sender).as(None)
          },
          ZStream
            .fromIterator(
              env.discover.discoverNodes.map(_.iterator)
            )
            .mapM(
              node =>
                nodes.connect(node)
                    .map(addr => (addr, Join(nodes.local)))
            )
        )
    )

}
