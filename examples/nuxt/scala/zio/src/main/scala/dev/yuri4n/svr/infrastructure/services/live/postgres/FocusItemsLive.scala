package dev.yuri4n.svr.infrastructure.services.live.postgres

import dev.yuri4n.svr.model.{FocusItem, FocusItemRow, User}
import dev.yuri4n.svr.services.FocusItems
import skunk.Session
import skunk.codec.all.*
import skunk.implicits.*
import zio.stream.Stream
import zio.stream.interop.fs2z.*
import zio.{Task, ZLayer}

final class FocusItemsLive(session: Session[Task]) extends FocusItems[Task]:

    override def create(
        creatorId: User.Id,
        title: String,
        description: Option[String],
    ): Task[FocusItem.Id] = session
      .unique(
        sql"""
            INSERT INTO focus_items (creator_id, title, description)
            VALUES ${(User.Id.skunkCodec *: text *: text.opt)}
            RETURNING id
        """.query(FocusItem.Id.skunkCodec),
      )
      .apply(creatorId, title, description)

    override def getAll: Stream[Throwable, FocusItemRow] = session
      .stream(
        sql"""
          SELECT id, creator_id, title, description
          FROM focus_items
      """.query(FocusItemRow.skunkCodec),
      )
      .apply(skunk.Void, 128)
      .toZStream()
end FocusItemsLive

object FocusItemsLive:
    def layer = ZLayer.fromFunction(FocusItemsLive.apply)
