package dev.yuri4n.exts.ziomigrations.examples

import dev.yuri4n.exts.ziomigrations.*
import zio.*
import zio.schema.*

import java.util.UUID

/** Example domain models */
case class User(
    id: UUID,
    email: String,
    name: String,
    age: Option[Int],
    createdAt: java.time.Instant,
)

object User:
    given Schema[User] = DeriveSchema.gen[User]

case class Post(
    id: Long,
    userId: UUID,
    title: String,
    content: String,
    published: Boolean,
    createdAt: java.time.Instant,
)

object Post:
    given Schema[Post] = DeriveSchema.gen[Post]

object ExampleUsage:

    // Define table using schema
    val usersTable = TableDef(
      name = "users",
      columns = List(
        ColumnDef.pk("id", Schema[UUID]),
        ColumnDef("email", Schema[String]).copy(unique = true),
        ColumnDef("name", Schema[String]),
        ColumnDef.nullable("age", Schema[Int]),
        ColumnDef("created_at", Schema[java.time.Instant])
          .copy(default = Some("CURRENT_TIMESTAMP")),
      ),
      indexes = List(
        IndexDef(List("email"), unique = true),
      ),
    )

    val postsTable = TableDef(
      name = "posts",
      columns = List(
        ColumnDef
          .pk("id", Schema[Long])
          .copy(default = Some("nextval('posts_id_seq')")),
        ColumnDef("user_id", Schema[UUID])
          .copy(references = Some(("users", "id"))),
        ColumnDef("title", Schema[String]),
        ColumnDef("content", Schema[String]),
        ColumnDef("published", Schema[Boolean])
          .copy(default = Some("false")),
        ColumnDef("created_at", Schema[java.time.Instant])
          .copy(default = Some("CURRENT_TIMESTAMP")),
      ),
      indexes = List(
        IndexDef(List("user_id")),
        IndexDef(List("published")),
      ),
      constraints = List(
        "CONSTRAINT posts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE",
      ),
    )

    /** Generate migration SQL */
    def generateMigrationSql: scala.Either[String, String] =
      for
          usersSql <- usersTable.createTableSql
          postsSql <- postsTable.createTableSql
      yield
          val allStatements = List(
            "-- Migration: Create users and posts tables",
            "",
            "-- Create sequence for posts",
            "CREATE SEQUENCE IF NOT EXISTS posts_id_seq;",
            "",
            usersSql,
            "",
          ) ++ usersTable.createIndexesSql ++ List(
            "",
            postsSql,
            "",
          ) ++ postsTable.createIndexesSql

          allStatements.mkString("\n")

    /** Example: Get Skunk codec for User */
    def getUserCodec: scala.Either[String, skunk.Codec[User]] =
      SchemaCodec.toCodec(User.given_Schema_User)

    /** Example: Use codec with Skunk queries */
    def example: Task[Unit] =
      getUserCodec match
          case Right(codec) =>
            // Now you can use this codec with Skunk queries
            /* val query: Query[Void, User] = sql"SELECT id, email, name, age, created_at FROM
             * users".query(codec) */
            ZIO.unit
          case Left(error)  =>
            ZIO.fail(new RuntimeException(s"Failed to create codec: $error"))

    /** Print migration SQL */
    def main(args: Array[String]): Unit =
      generateMigrationSql match
          case Right(sql)  => println(sql)
          case Left(error) => println(s"Error: $error")
end ExampleUsage
