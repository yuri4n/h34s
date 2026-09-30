package dev.yuri4n.exts

import zio.schema.{Schema, derived}

import java.io.File
import zio.prelude.NonEmptyList

package object ziomigrations:

    sealed trait MigrationSource

    opaque type MigrationFile = File & MigrationSource
    object MigrationFile:
        def apply(file: File): MigrationFile = file.asInstanceOf[MigrationFile]

    final case class MigrationConfig(
        tableName: String,
        migrationSources: NonEmptyList[MigrationSource],
    )

    final case class Migration(id: Migration.Id, name: String, appliedAt: java.time.Instant)
        derives Schema

    object Migration:
        opaque type Id = Long
        object Id:
            given Schema[Id] = Schema[Long]

    lazy val createMigrationsTable = TableDef.forSchema[Migration]("migrations")
end ziomigrations

object Example extends App:
    println(
      ziomigrations.createMigrationsTable.toOption.get.createTableSql.toOption.get,
    )
