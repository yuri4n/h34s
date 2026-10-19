package dev.yuri4n.svr.infrastructure.services.live.postgres

import dev.yuri4n.svr.services.Plans
import skunk.Session
import zio.Task

final case class PlansLive(session: Session[Task]) extends Plans[Task]
