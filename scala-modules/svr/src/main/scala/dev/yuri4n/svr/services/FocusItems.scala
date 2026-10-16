package dev.yuri4n.svr.services

import dev.yuri4n.svr.model.{FocusItem, FocusItemRow, User}
import zio.stream.Stream

trait FocusItems[F[_]]:
    def create(
        creatorId: User.Id,
        title: String,
        description: Option[String],
    ): F[FocusItem.Id]

    def getAll: Stream[Throwable, FocusItemRow]
