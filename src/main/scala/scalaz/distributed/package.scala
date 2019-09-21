package scalaz

import scalaz.zio.IO

package object distributed {
  type Distributed[A] = IO[Error, A]

  val client: Client = Client.default
}
