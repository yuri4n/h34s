package dev.yuri4n.svr.services

import dev.yuri4n.exts.zioh3.TaskStream
import dev.yuri4n.svr.model.{FocusItem, FocusItemRow, User}

trait FocusItems[F[_]]:
    def create(
        creatorId: User.Id,
        title: String,
        description: Option[String],
    ): F[FocusItem.Id]

    def getAll: TaskStream[FocusItemRow]
