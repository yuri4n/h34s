package dev.yuri4n.svr

import org.typelevel.twiddles.Iso
import skunk.codec.all.*
import zio.{Random, UIO}

import java.time.LocalDateTime
import java.util.UUID

object model:
    final case class User(id: User.Id, name: String)

    object User:
        opaque type Id = UUID
        object Id:
            def skunkCodec: skunk.Codec[Id] = uuid
            def apply: UIO[Id]              = Random.nextUUID

    object FocusItem:
        opaque type Id = UUID

        object Id:
            def skunkCodec: skunk.Codec[Id]    = uuid
            extension (id: Id) def value: UUID = id

    final case class FocusItemRow(
        id: FocusItem.Id,
        creatorId: User.Id,
        title: String,
        description: Option[String],
    )

    object FocusItemRow:
        def skunkCodec: skunk.Codec[FocusItemRow] =
          (FocusItem.Id.skunkCodec *: User.Id.skunkCodec *: text *: text.opt).to[FocusItemRow]

    final case class PlanOrderRow(
        id: PlanOrderRow.Id,
        planId: Plan.Id,
        focusItemId: FocusItem.Id,
        order: Int,
    )

    object PlanOrderRow:
        def skunkCodec: skunk.Codec[PlanOrderRow] =
          (int8 *: Plan.Id.skunkCodec *: FocusItem.Id.skunkCodec *: int4).to[PlanOrderRow]

        opaque type Id = Long
        object Id:
            extension (id: Id) def value: Long = id

    final case class Plan(
        id: Plan.Id,
        creator: User,
        name: String,
    )

    object Plan:
        opaque type Id = UUID
        object Id:
            def skunkCodec: skunk.Codec[Id]    = uuid
            extension (id: Id) def value: UUID = id

    final case class PlanRow(id: Plan.Id, creatorId: User.Id, name: String)

    final case class SessionRow(
        id: Session.Id,
        creatorId: User.Id,
        name: String,
        planId: Plan.Id,
        plannedToStartAt: LocalDateTime,
        startedAt: Option[LocalDateTime],
        finishedAt: Option[LocalDateTime],
    )

    object SessionRow:
        def skunkCodec: skunk.Codec[SessionRow] =
          (Session.Id.skunkCodec *: User.Id.skunkCodec *: text *: Plan.Id.skunkCodec *: timestamp *: timestamp.opt *: timestamp.opt)
            .to[SessionRow]

    object Session:
        opaque type Id = UUID
        object Id:
            def skunkCodec: skunk.Codec[Id]    = uuid
            extension (id: Id) def value: UUID = id

end model
