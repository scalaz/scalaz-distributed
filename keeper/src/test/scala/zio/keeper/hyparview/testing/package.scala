package zio.keeper.hyparview

import zio.Has

package object testing {

  type TestPeerService = Has[TestPeerService.Service]

}
