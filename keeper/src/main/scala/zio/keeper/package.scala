package zio

import izumi.reflect.Tags.Tag
import zio.stream._

package object keeper extends MembershipProtocol with ConsensusProtocol {
  type Keeper[A] = Membership[A] with Consensus

  def broadcast[A: Tag](data: A): ZIO[Membership[A], SerializationError, Unit] =
    ZIO.accessM(_.get.broadcast(data))

  def send[A: Tag](data: A, recepient: NodeAddress): URIO[Membership[A], Unit] =
    ZIO.accessM(_.get.send(data, recepient))

  def receive[A: Tag]: ZStream[Membership[A], Nothing, (NodeAddress, A)] =
    ZStream.accessStream(_.get.receive)
}
