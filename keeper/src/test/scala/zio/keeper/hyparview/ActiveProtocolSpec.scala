package zio.keeper.hyparview

import zio.keeper.KeeperSpec
import zio.test._
import zio.keeper.transport.testing.MockConnection._
import zio.keeper.NodeAddress
import zio._
import zio.keeper.gens
import zio.test.Assertion._
import zio.logging.Logging
import zio.keeper.transport.testing.MockConnection
import zio.keeper.hyparview.ViewEvent.UnhandledMessage
import zio.keeper.hyparview.ViewEvent.AddedToActiveView
import zio.keeper.hyparview.ViewEvent.RemovedFromActiveView
import zio.keeper.hyparview.ViewEvent.PeerMessageReceived
import zio.clock.Clock

object ActiveProtocolSpec extends KeeperSpec {

  def spec =
    suite("ActiveProtocol")(
      suite("on receiving peerMessage")(
        testM("will forward message to views") {
          checkM(gens.nodeAddress, gens.hyparview.peerMessage) {
            case (address, message) =>
              val test =
                for {
                  result <- run(
                             address,
                             emit(message)
                           )
                  events <- Views.events.take(3).runCollect
                } yield result && assert(events)(
                  hasSameElements(
                    List(
                      AddedToActiveView(address),
                      PeerMessageReceived(address, message),
                      RemovedFromActiveView(address)
                    )
                  )
                )
              test.provideSomeLayer(env())
          }
        }
      ),
      suite("on receiving forwardjoin")(
        testM("should send ForwardJoinReply if active view is empty") {
          val gen = for {
            a1 <- gens.nodeAddress
            a2 <- gens.nodeAddress.filterNot(_ == a1)
          } yield (a1, a2)
          checkM(gen) {
            case (a1, a2) =>
              val test =
                for {
                  result <- run(
                             a1,
                             emit(Message.ForwardJoin(a2, TimeToLive(2)))
                           )
                  events <- Views.events.take(3).runCollect
                } yield result && assert(events)(
                  hasSameElements(
                    List(
                      AddedToActiveView(a1),
                      UnhandledMessage(a2, Message.ForwardJoinReply(address(0))),
                      RemovedFromActiveView(a1)
                    )
                  )
                )
              test.provideSomeLayer(env())
          }
        }
      ),
      suite("on receiving shuffleReply")(
        testM("will add received nodes to passiveView") {
          val gen = for {
            a1 <- gens.nodeAddress
            a2 <- gens.nodeAddress.filterNot(_ == a1)
          } yield (a1, a2)
          checkM(gen) {
            case (a1, a2) =>
              val test =
                for {
                  result <- run(
                             a1,
                             emit(Message.ShuffleReply(Set(a2), Set.empty))
                           )
                  inPassive <- Views.passiveView.map(_.contains(a2)).commit
                } yield result && assert(inPassive)(isTrue)
              test.provideSomeLayer(env())
          }
        },
        testM("will remove nodes if passive view is full") {
          val gen = for {
            a1 <- gens.nodeAddress
            a2 <- gens.nodeAddress.filterNot(_ == a1)
            a3 <- gens.nodeAddress.filterNot(a => (a == a1) || (a == a2))
          } yield (a1, a2, a3)
          checkM(gen) {
            case (a1, a2, a3) =>
              val test =
                for {
                  _ <- Views.addToPassiveView(a1).commit
                  result <- run(
                             a2,
                             emit(Message.ShuffleReply(Set(a3), Set.empty))
                           )
                  passiveView <- Views.passiveView.commit
                } yield result && assert(passiveView)(equalTo(Set(a3)))
              test.provideSomeLayer(env(passiveViewCapacity = 1))
          }
        }
      ),
      suite("on receiving disconnect")(
        testM("should keep remote in passive view if alive") {
          checkM(gens.nodeAddress) { address =>
            val test =
              for {
                result <- run(
                           address,
                           emit(Message.Disconnect(true))
                         )
                passive <- Views.passiveView.commit
              } yield result && assert(passive)(contains(address))
            test.provideSomeLayer(env())
          }
        },
        testM("should not keep remote in passive view if not alive") {
          checkM(gens.nodeAddress) { address =>
            val test = for {
              result <- run(
                         address,
                         emit(Message.Disconnect(false))
                       )
              passive <- Views.passiveView.commit
            } yield result && assert(passive)(isEmpty)
            test.provideSomeLayer(env())
          }
        }
      )
    )

  private def run(sender: NodeAddress, script: MockConnection[Nothing, Message, Message]) = {
    val makeConnection = emit(Message.Join(sender)) ++ script
    makeConnection.use { con =>
      for {
        result <- protocols.runInitial(con).run
      } yield assert(result)(succeeds(anything))
    }
  }

  private def env(passiveViewCapacity: Int = 10) =
    ZLayer.identity[Sized with Clock] ++
      TRandom.live ++
      Logging.ignore ++
      HyParViewConfig.static(address(0), 10, passiveViewCapacity, 5, 3, 2, 2, 3, 256, 256, 256) >+>
      Views.live

}
