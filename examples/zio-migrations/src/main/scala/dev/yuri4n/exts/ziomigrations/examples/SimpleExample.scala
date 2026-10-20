package dev.yuri4n.exts.ziomigrations.examples

import dev.yuri4n.exts.ziomigrations.*
import skunk.*
import zio.*
import zio.schema.*

/** Simple example showing primitive type codecs */
object SimpleExample:

    // Example 1: Simple primitive codec
    val stringCodec: scala.Either[String, Codec[String]] =
      SchemaCodec.toCodec(Schema[String])

    val intCodec: scala.Either[String, Codec[Int]] =
      SchemaCodec.toCodec(Schema[Int])

    val uuidCodec: scala.Either[String, Codec[java.util.UUID]] =
      SchemaCodec.toCodec(Schema[java.util.UUID])

    // Example 2: Optional types
    val optionalStringCodec: scala.Either[String, Codec[Option[String]]] =
      SchemaCodec.toCodec(Schema[Option[String]])

    // Example 3: SQL type generation
    val stringSqlType: scala.Either[String, String] =
      SchemaCodec.toSqlType(Schema[String]) // Returns "TEXT"

    val intSqlType: scala.Either[String, String] =
      SchemaCodec.toSqlType(Schema[Int]) // Returns "INTEGER"

    val timestampSqlType: scala.Either[String, String] =
      SchemaCodec.toSqlType(Schema[java.time.Instant]) // Returns "TIMESTAMPTZ"

    // Example 4: Column definition
    val idColumn    = ColumnDef.pk("id", Schema[java.util.UUID])
    val emailColumn = ColumnDef("email", Schema[String]).copy(unique = true)
    val ageColumn   = ColumnDef.nullable("age", Schema[Int])

    // Print examples
    def main(args: Array[String]): Unit =
        println("=== Codec Examples ===")
        println(s"String codec: $stringCodec")
        println(s"Int codec: $intCodec")
        println(s"Optional String codec: $optionalStringCodec")

        println("\n=== SQL Type Examples ===")
        println(s"String SQL type: $stringSqlType")
        println(s"Int SQL type: $intSqlType")
        println(s"Timestamp SQL type: $timestampSqlType")

        println("\n=== Column SQL Examples ===")
        println(s"ID column: ${idColumn.toSql}")
        println(s"Email column: ${emailColumn.toSql}")
        println(s"Age column: ${ageColumn.toSql}")
    end main
end SimpleExample
