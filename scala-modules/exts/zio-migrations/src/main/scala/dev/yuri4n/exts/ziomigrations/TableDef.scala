package dev.yuri4n.exts.ziomigrations

import skunk.*
import skunk.codec.all.*
import zio.schema.Schema

/** Represents a database column with its schema */
case class ColumnDef[A](
    name: String,
    schema: Schema[A],
    nullable: Boolean = false,
    primaryKey: Boolean = false,
    unique: Boolean = false,
    default: Option[String] = None,
    references: Option[(String, String)] = None, // (table, column)
):
    def codec: scala.Either[String, Codec[A]] = SchemaCodec.toCodec(schema)

    def sqlType: scala.Either[String, String] = SchemaCodec.toSqlType(schema)

    def toSql: scala.Either[String, String] =
      sqlType.map { tpe =>
          val parts = List(
            Some(name),
            Some(tpe),
            if !nullable then Some("NOT NULL") else None,
            if primaryKey then Some("PRIMARY KEY") else None,
            if unique then Some("UNIQUE") else None,
            default.map(d => s"DEFAULT $d"),
            references.map { case (table, col) => s"REFERENCES $table($col)" },
          ).flatten
          parts.mkString(" ")
      }
end ColumnDef

object ColumnDef:
    def apply[A](name: String, schema: Schema[A]): ColumnDef[A] =
      new ColumnDef(name, schema)

    def pk[A](name: String, schema: Schema[A]): ColumnDef[A] =
      new ColumnDef(name, schema, primaryKey = true)

    def nullable[A](name: String, schema: Schema[A]): ColumnDef[A] =
      new ColumnDef(name, schema, nullable = true)
end ColumnDef

/** Represents a database table with columns defined by schemas */
case class TableDef(
    name: String,
    columns: List[ColumnDef[?]],
    indexes: List[IndexDef] = Nil,
    constraints: List[String] = Nil,
):

    /** Generate CREATE TABLE DDL */
    def createTableSql: scala.Either[String, String] =
      traverseColumns(columns)(_.toSql).map { cols =>
          val allParts = cols ++ constraints
          s"""CREATE TABLE IF NOT EXISTS $name (
         |  ${allParts.mkString(",\n  ")}
         |);""".stripMargin
      }

    /** Generate CREATE INDEX statements */
    def createIndexesSql: List[String] =
      indexes.map(_.toSql(name))

    private def traverseColumns(
        cols: List[ColumnDef[?]],
    )(f: ColumnDef[?] => scala.Either[String, String]): scala.Either[String, List[String]] =
      cols.foldLeft[scala.Either[String, List[String]]](scala.Right(Nil)) { (acc, col) =>
        for
            items <- acc
            value <- f(col)
        yield items :+ value
      }
end TableDef

object TableDef:
    /** Create a TableDef from a Schema by deriving columns */
    def forSchema[A: Schema](
        name: String,
        indexes: List[IndexDef] = Nil,
        constraints: List[String] = Nil,
    ): scala.Either[String, TableDef] =
      deriveColumnsFromSchema(summon[Schema[A]]).map { cols =>
        TableDef(name, cols, indexes, constraints)
      }

    /** Derive columns from a record schema */
    private def deriveColumnsFromSchema[A](
        schema: Schema[A],
    ): scala.Either[String, List[ColumnDef[?]]] =
      schema match
          case record: Schema.Record[?] =>
            scala.Right(record.fields.toList.map { field =>
                val isOptional = field.schema match
                    case _: Schema.Optional[?] => true
                    case _                     => false
                ColumnDef(
                  name = field.name,
                  schema = field.schema,
                  nullable = isOptional,
                )
            })
          case _                        =>
            scala.Left("Can only derive columns from Record schemas")
end TableDef

case class IndexDef(
    columns: List[String],
    unique: Boolean = false,
    indexType: Option[String] = None, // BTREE, HASH, GIN, etc.
    name: Option[String] = None,
):
    def toSql(tableName: String): String =
        val indexName = name.getOrElse(s"idx_${tableName}_${columns.mkString("_")}")
        val uniqueStr = if unique then "UNIQUE " else ""
        val typeStr   = indexType.map(t => s" USING $t").getOrElse("")
        s"CREATE ${uniqueStr}INDEX IF NOT EXISTS $indexName ON $tableName$typeStr (${columns.mkString(", ")});"
end IndexDef
