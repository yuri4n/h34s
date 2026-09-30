package dev.yuri4n.svr.infrastructure.services.live.postgres

import dev.yuri4n.svr.services.PlansOrders
import zio.Task

final case class PlansOrdersLive() extends PlansOrders[Task]
