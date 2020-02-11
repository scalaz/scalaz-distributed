package zio.membership.swim

import upickle.default._
import zio.membership.Member
import zio.membership.swim.GossipState.StateDiff

import scala.collection.immutable.SortedSet

case class GossipState[A](members: SortedSet[Member[A]]) extends AnyVal {

  def addMember(member: Member[A]) =
    copy(members = this.members + member)

  def diff(other: GossipState[A]): StateDiff[A] =
    StateDiff(
      this.members.diff(other.members),
      other.members.diff(this.members)
    )

  def merge(other: GossipState[A]) =
    copy(members = this.members ++ other.members)

  def removeMember(member: Member[A]) =
    copy(members = this.members - member)

  override def toString: String = s"GossipState[${members.mkString(",")}] "
}

object GossipState {
  def Empty[A: Ordering] = GossipState(SortedSet.empty[Member[A]])

  final case class StateDiff[A](local: SortedSet[Member[A]], remote: SortedSet[Member[A]])

  implicit def gossipStateRw[A: ReadWriter] = macroRW[GossipState[A]]
}