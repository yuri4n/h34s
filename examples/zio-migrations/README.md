# ZIO Migrations

A library for type-safe database migrations using ZIO Schema and Skunk codecs.

## Features

- **Schema to Codec**: Convert `zio.schema.Schema[A]` to `skunk.Codec[A]`
- **Schema to DDL**: Generate PostgreSQL DDL from schemas
- **Type-safe tables**: Define tables using case classes and schemas
- **Automatic column derivation**: Derive table columns from case class schemas

## Usage

### 1. Define your domain models with schemas

```scala
import zio.schema.*
import java.util.UUID

case class User(
  id: UUID,
  email: String,
  name: String,
  age: Option[Int],
  createdAt: java.time.Instant
)

object User {
  given Schema[User] = DeriveSchema.gen[User]
}
```

### 2. Create table definitions

```scala
import dev.yuri4n.exts.ziomigrations.*

val usersTable = TableDef(
  name = "users",
  schema = User.given_Schema_User,
  columns = List(
    ColumnDef.pk("id", Schema[UUID]),
    ColumnDef("email", Schema[String]).copy(unique = true),
    ColumnDef("name", Schema[String]),
    ColumnDef.nullable("age", Schema[Int]),
    ColumnDef("created_at", Schema[java.time.Instant])
      .copy(default = Some("CURRENT_TIMESTAMP"))
  ),
  indexes = List(
    IndexDef(List("email"), unique = true)
  )
)
```

### 3. Generate migration SQL

```scala
val migrationSql: Either[String, String] = usersTable.createTableSql
// Produces:
// CREATE TABLE IF NOT EXISTS users (
//   id UUID PRIMARY KEY,
//   email TEXT NOT NULL UNIQUE,
//   name TEXT NOT NULL,
//   age INTEGER,
//   created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
// );
```

### 4. Get Skunk codecs for primitive types

```scala
import dev.yuri4n.exts.ziomigrations.SchemaCodec

// Primitive types work fully
val stringCodec: scala.Either[String, Codec[String]] = 
  SchemaCodec.toCodec(Schema[String])

val uuidCodec: scala.Either[String, Codec[UUID]] = 
  SchemaCodec.toCodec(Schema[UUID])

val optionalIntCodec: scala.Either[String, Codec[Option[Int]]] = 
  SchemaCodec.toCodec(Schema[Option[Int]])

// For complex types like case classes, use manual codecs:
case class User(id: UUID, email: String, name: String)

val userCodec: Codec[User] = 
  (uuid *: varchar *: varchar).imap {
    case (id, email, name) => User(id, email, name)
  } { user =>
    (user.id, user.email, user.name)
  }

// Or use JSONB for complex structures:
import skunk.circe.codec.all._
import io.circe.generic.auto._

val userJsonCodec: Codec[User] = jsonb[User]
```
```

## Supported Types

### Fully Supported (codecs work)
- **Primitive Types**: String, Boolean, Int, Short, Long, Float, Double
- **UUID**: UUID
- **Numeric Types**: BigDecimal, BigInt
- **Date/Time Types**: LocalDate, LocalDateTime, LocalTime, Instant, OffsetDateTime, ZonedDateTime, Duration
- **Optional Types**: `Option[A]` for any supported type A → nullable columns

### Partially Supported (SQL DDL only)
- **Sequences/Lists**: `Seq[A]`, `List[A]` → JSONB (codecs not yet implemented)
- **Case Classes/Records**: → JSONB (codecs not yet implemented)
- **Enums/ADTs**: → custom handling needed (codecs not yet implemented)

For complex types (records, enums, sequences), you can:
- Use `SchemaCodec.toSqlType` to generate DDL
- Manually construct Skunk codecs using `jsonb[A]` or custom composite types
- Define columns explicitly in `TableDef`

## API

### SchemaCodec

```scala
object SchemaCodec {
  def toCodec[A](schema: Schema[A]): Either[String, Codec[A]]
  def toSqlType[A](schema: Schema[A]): Either[String, String]
}
```

### TableDef

```scala
case class TableDef[A](
  name: String,
  schema: Schema[A],
  columns: List[ColumnDef[?]] = Nil,
  indexes: List[IndexDef] = Nil,
  constraints: List[String] = Nil
) {
  def createTableSql: Either[String, String]
  def createIndexesSql: List[String]
  def codec: Either[String, Codec[A]]
}
```

### ColumnDef

```scala
case class ColumnDef[A](
  name: String,
  schema: Schema[A],
  nullable: Boolean = false,
  primaryKey: Boolean = false,
  uCurrent Limitations & Workarounds

### 1. Case Class Codecs
**Status**: Not implemented  
**Workaround**: Manually construct tupled codecs with `imap`
```scala
case class User(id: UUID, name: String)
val codec = (uuid *: varchar).imap {
  case (id, name) => User(id, name)
} { u => (u.id, u.name) }
```

### 2. Enum/ADT Codecs
**Status**: Not implemented  
**Workaround**: Use string encoding or custom codecs
```scala
enum Status { case Active, Inactive }
val statusCodec = varchar.imap(Status.valueOf)(_.toString)
```

### 3. Collection Codecs (List, Seq, etc.)
**Status**: Not implemented  
**Workaround**: Use JSONB or PostgreSQL array types
```scala
import skunk.circe.codec.all._
val listCodec = jsonb[List[String]]
```

### 4. Schema-driven migrations
**Status**: DDL generation works, but no auto-diff between versions yet  
**Use**: Manually write migration files or use generated DDL as reference
  def apply[A](name: String, schema: Schema[A]): ColumnDef[A]
  def pk[A](name: String, schema: Schema[A]): ColumnDef[A]
  def nullable[A](name: String, schema: Schema[A]): ColumnDef[A]
}
```

## Example

See [ExampleUsage.scala](src/main/scala/dev/yuri4n/exts/ziomigrations/examples/ExampleUsage.scala) for complete examples.

## Limitations

- Record (case class) codecs are simplified - full tuple construction needs refinement
- Enum codecs need discriminator column support for ADTs
- Array types default to JSONB (can be extended for native PostgreSQL arrays)
- Composite types are represented as JSONB by default

## Future Enhancements

- [ ] Full case class tuple codec support
- [ ] ADT discriminator columns
- [ ] PostgreSQL array type support
- [ ] Migration versioning and runner
- [ ] Schema diff and migration generation
- [ ] Support for other databases (MySQL, SQLite)
