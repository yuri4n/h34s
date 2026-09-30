package dev.yuri4n.svr.app

import dev.yuri4n.svr.services.FocusItems
import zio.ZIO
import zio.ZLayer
import zio.Task

type Services[F[_]] = FocusItems[F]

object Services:
    def layer = ZLayer.fromZIO(ZIO.service[Services[Task]])
