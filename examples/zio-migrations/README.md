# zio-migrations example

<!-- claude 2026-10-20: created: what the example is and what it is not -->

This directory holds an unpublished example, not an h34s module. It derives Skunk codecs for primitive and optional ZIO Schema types, and it writes PostgreSQL `CREATE TABLE` and `CREATE INDEX` statements from column definitions.

It is not a migration tool. Nothing in it applies, versions or compares statements. It also depends on no h34s module, so it does not show how to use h34s. For database work in a handler, follow the PostgreSQL guide: [ZIO: Skunk in a `ZShared` layer](../../docs/content/1.guide/08.database.md#zio-skunk-in-a-zshared-layer).

## Status

<!-- claude 2026-10-20: created: build facts, CI coverage and what was never tested -->

- The sbt project is `zioMigrationsExt`, its name is `zio-migrations-ext`, and its package is `dev.yuri4n.exts.ziomigrations`. These names change with the coordinate rename ([#22](https://github.com/yuri4n/h34s/issues/22)).
- It is not published: `build.sbt` sets `publish / skip := true`, and the project is not in `releaseModules`. The root aggregate builds it next to the other examples; the library aggregate does not.
- It uses the shared CommonJS settings of `configureCommonJsProject` in `build.sbt`, the same dependency set as the ZIO example. Its code imports only ZIO, ZIO Schema, ZIO Prelude and Skunk.
- CI compiles it (`zioMigrationsExt/compile` in the validate job of `.github/workflows/scala.yml`). It has no tests, and nobody has run its codecs or its SQL against PostgreSQL.

## Compile it

<!-- claude 2026-10-20: created: the compile command and its expected result -->

You need the repository's toolchain: Java 25 and sbt 1.11.7. From the repository root, run:

```sh
sbt zioMigrationsExt/compile
```

Expected result: sbt prints `[success]`. The build sets no main class; to run one of the printing examples, see [Print the SQL](#print-the-sql).

## What the code offers

<!-- claude 2026-10-20: created: the files and the API as the sources define them -->

| File | Contents |
| --- | --- |
| `SchemaCodec.scala` | `SchemaCodec.toCodec[A](schema: Schema[A]): Either[String, Codec[A]]` and `SchemaCodec.toSqlType[A](schema: Schema[A]): Either[String, String]` |
| `TableDef.scala` | `ColumnDef`, `TableDef`, `TableDef.forSchema` and `IndexDef` |
| `package.scala` | `Migration`, `MigrationConfig`, `MigrationSource`, `MigrationFile`, `createMigrationsTable`, and `dev.yuri4n.exts.Example`, which prints that table's SQL |
| `examples/ExampleUsage.scala` | Two hand-written tables (`users`, `posts`), the SQL for both, and a codec attempt for a case class |
| `examples/SimpleExample.scala` | Codecs and SQL types for single values, and three columns |

The source files are under `src/main/scala/dev/yuri4n/exts/ziomigrations/`. `Migration`, `MigrationConfig`, `MigrationSource` and `MigrationFile` are declarations only: no code reads them.

### Codecs and SQL types

<!-- claude 2026-10-20: created: the codec and SQL type mapping, with its wrong codecs -->

`SchemaCodec.toCodec` returns a Skunk codec for a primitive schema, `.opt` of the inner codec for `Option[A]`, and the inner result for a lazy schema. `SchemaCodec.toSqlType` returns the column type. For `Option[A]` it returns the type of `A`: nullability belongs to the column.

| Scala type | `toCodec` | `toSqlType` |
| --- | --- | --- |
| `String` | `varchar` | `TEXT` |
| `Boolean` | `bool` | `BOOLEAN` |
| `Short`, `Int`, `Long` | `int2`, `int4`, `int8` | `SMALLINT`, `INTEGER`, `BIGINT` |
| `Float`, `Double` | `float4`, `float8` | `REAL`, `DOUBLE PRECISION` |
| `java.util.UUID` | `uuid` | `UUID` |
| `java.time.LocalDate`, `LocalTime`, `LocalDateTime` | `date`, `time`, `timestamp` | `DATE`, `TIME`, `TIMESTAMP` |
| `java.time.OffsetTime`, `OffsetDateTime` | `timetz`, `timestamptz` | `TIMETZ`, `TIMESTAMPTZ` |
| `java.time.Instant`, `ZonedDateTime` | `timestamptz`, mapped | `TIMESTAMPTZ` |
| `java.time.Duration` | `interval` | `INTERVAL` |
| `java.time.DayOfWeek`, `Month`, `MonthDay`, `Period`, `YearMonth`, `ZoneId`, `ZoneOffset` | `varchar`, parsed and printed | `TEXT` |
| `java.time.Year` | `int4`, mapped | `TEXT` |
| `Chunk[Byte]`, `Char`, `java.math.BigDecimal`, `java.math.BigInteger` | a codec of another type (see below) | `BYTEA`, `CHAR(1)`, `NUMERIC`, `NUMERIC` |

For records (case classes), enums, sequences, transformed schemas and every other shape, `toCodec` returns a `Left` with a message. `ExampleUsage.getUserCodec` is therefore always a `Left`, and `ExampleUsage.example` always fails. `toSqlType` never returns a `Left`: sequences, records, enums and every other shape become `JSONB`, a transformed schema gets the type of its underlying schema, and an unlisted primitive becomes `TEXT`.

Do not use the codecs for `Chunk[Byte]`, `Char`, `java.math.BigDecimal` and `java.math.BigInteger`. `toCodec` gives them `bytea` (a `Codec[Array[Byte]]`), `bpchar(1)` (a `Codec[String]`), `numeric` (a `Codec[scala.math.BigDecimal]`) and a `Codec[scala.math.BigInt]`. The cast inside `toCodec` hides the difference, so a decoded value has a different type than the schema says. The codec and the column type can also disagree: a `Year` column is `TEXT`, but its codec is `int4`.

### Columns, tables and indexes

<!-- claude 2026-10-20: created: ColumnDef, TableDef, IndexDef and forSchema as the code writes them -->

- `ColumnDef[A](name, schema, nullable = false, primaryKey = false, unique = false, default = None, references = None)` describes one column. `ColumnDef(name, schema)`, `ColumnDef.pk(name, schema)` and `ColumnDef.nullable(name, schema)` set the common cases. `toSql` writes the name and the type, then, in this order: `NOT NULL` unless the column is nullable, `PRIMARY KEY`, `UNIQUE`, `DEFAULT <default>` and `REFERENCES <table>(<column>)`. A primary key column therefore reads `id UUID NOT NULL PRIMARY KEY`.
- `TableDef(name, columns, indexes = Nil, constraints = Nil)` describes a table. `createTableSql` returns `CREATE TABLE IF NOT EXISTS <name> (...)` with the columns, then the constraint strings. `createIndexesSql` returns one statement per index.
- `IndexDef(columns, unique = false, indexType = None, name = None)` describes an index. `toSql(table)` returns `CREATE [UNIQUE ]INDEX IF NOT EXISTS <name> ON <table>[ USING <type>] (<columns>);`. The default name is `idx_<table>_<columns joined with _>`.
- `TableDef.forSchema[A: Schema](name, indexes = Nil, constraints = Nil)` derives the columns from a record schema: one column per field, nullable when the field's schema is `Schema.Optional`, and never a primary key. The column names are the Scala field names, without quoting, so PostgreSQL folds `appliedAt` to `appliedat`. For any other schema it returns `Left("Can only derive columns from Record schemas")`.

Names, defaults, constraints and index types go into the SQL text exactly as written, without quoting or escaping. Never build them from user input.

## Print the SQL

<!-- claude 2026-10-20: created: how to run the printing examples and what they print -->

Three objects print what the code generates: `dev.yuri4n.exts.ziomigrations.examples.ExampleUsage` (the `users` and `posts` tables), `dev.yuri4n.exts.ziomigrations.examples.SimpleExample` (single codecs, SQL types and columns) and `dev.yuri4n.exts.Example` (the derived `migrations` table). The build does not set a main class, so set one for the run. From the repository root, run:

```sh
sbt 'set zioMigrationsExt / scalaJSUseMainModuleInitializer := true' \
  'set zioMigrationsExt / Compile / mainClass := Some("dev.yuri4n.exts.ziomigrations.examples.ExampleUsage")' \
  'zioMigrationsExt / run'
```

Expected result: Node prints the SQL, and sbt prints `[success]`. The output starts like this:

```sql
-- Migration: Create users and posts tables

-- Create sequence for posts
CREATE SEQUENCE IF NOT EXISTS posts_id_seq;

CREATE TABLE IF NOT EXISTS users (
  id UUID NOT NULL PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  age INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users (email);
```

With `dev.yuri4n.exts.Example` as the main class, the output is the table that `TableDef.forSchema[Migration]` derives. It has no primary key, and its last column is `appliedAt`:

```sql
CREATE TABLE IF NOT EXISTS migrations (
  id BIGINT NOT NULL,
  name TEXT NOT NULL,
  appliedAt TIMESTAMPTZ NOT NULL
);
```

The statements are text only. Nothing sends them to a database.

## Limits

<!-- claude 2026-10-20: created: what the example does not do, and where to look instead -->

- No codec for case classes, enums or collections. Write those Skunk codecs yourself; the ZIO example does so in `examples/nuxt/scala/zio/src/main/scala/dev/yuri4n/svr/model.scala`, for example `(FocusItem.Id.skunkCodec *: User.Id.skunkCodec *: text *: text.opt).to[FocusItemRow]`.
- No JSON column codec. The build has no Skunk JSON module, so a `JSONB` column from `toSqlType` has no matching codec here.
- No migration runner, no version table handling and no schema diff.
- PostgreSQL only.
