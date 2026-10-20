package dev.yuri4n.exts.ziomigrations

import skunk.*
import skunk.codec.all.*
import zio.Chunk
import zio.schema.{Schema, StandardType}

object SchemaCodec:

    /** Converts a ZIO Schema to a Skunk Codec */
    def toCodec[A](schema: Schema[A]): scala.Either[String, Codec[A]] =
      toCodecImpl(schema).asInstanceOf[scala.Either[String, Codec[A]]]

    private def toCodecImpl[A](schema: Schema[A]): scala.Either[String, Codec[?]] =
      schema match
          // Primitive types
          case primitive: Schema.Primitive[?] =>
            primitiveCodec(primitive.standardType)

          // Optional types
          case optional: Schema.Optional[?] =>
            toCodecImpl(optional.schema).map(_.opt)

          // Sequences - simplified to JSONB for now
          case _: Schema.Sequence[?, ?, ?] =>
            scala.Left(
              "Sequence codecs not yet implemented - arrays should use JSONB or manual construction",
            )

          // Case classes (records)
          case record: Schema.Record[?] =>
            recordCodec(record.fields)

          // Enums
          case enumSchema: Schema.Enum[?] =>
            enumCodec(enumSchema)

          // Transform - simplified
          case _: Schema.Transform[?, ?, ?] =>
            scala.Left("Transform codecs not yet implemented - use manual codec construction")

          // Lazy
          case lazySchema: Schema.Lazy[?] =>
            toCodecImpl(lazySchema.schema)

          case _ =>
            scala.Left(s"Unsupported schema type: ${schema.getClass.getSimpleName}")

    private def primitiveCodec(standardType: StandardType[?]): scala.Either[String, Codec[?]] =
      standardType match
          case StandardType.StringType         => scala.Right(varchar)
          case StandardType.BoolType           => scala.Right(bool)
          case StandardType.ShortType          => scala.Right(int2)
          case StandardType.IntType            => scala.Right(int4)
          case StandardType.LongType           => scala.Right(int8)
          case StandardType.FloatType          => scala.Right(float4)
          case StandardType.DoubleType         => scala.Right(float8)
          case StandardType.BinaryType         => scala.Right(bytea)
          case StandardType.CharType           => scala.Right(bpchar(1))
          case StandardType.UUIDType           => scala.Right(uuid)
          case StandardType.BigDecimalType     => scala.Right(numeric)
          case StandardType.BigIntegerType     => scala.Right(numeric.imap(_.toBigInt)(BigDecimal(_)))
          case StandardType.DayOfWeekType      =>
            scala.Right(varchar.imap(java.time.DayOfWeek.valueOf)(_.toString))
          case StandardType.MonthType          =>
            scala.Right(varchar.imap(java.time.Month.valueOf)(_.toString))
          case StandardType.MonthDayType       =>
            scala.Right(varchar.imap(java.time.MonthDay.parse)(_.toString))
          case StandardType.PeriodType         =>
            scala.Right(varchar.imap(java.time.Period.parse)(_.toString))
          case StandardType.YearType           => scala.Right(int4.imap(java.time.Year.of)(_.getValue))
          case StandardType.YearMonthType      =>
            scala.Right(varchar.imap(java.time.YearMonth.parse)(_.toString))
          case StandardType.ZoneIdType         => scala.Right(varchar.imap(java.time.ZoneId.of)(_.getId))
          case StandardType.ZoneOffsetType     =>
            scala.Right(varchar.imap(java.time.ZoneOffset.of)(_.getId))
          case StandardType.DurationType       => scala.Right(interval)
          case StandardType.InstantType        =>
            scala.Right(
              timestamptz.imap(_.toInstant)(
                java.time.OffsetDateTime.ofInstant(_, java.time.ZoneOffset.UTC),
              ),
            )
          case StandardType.LocalDateType      => scala.Right(date)
          case StandardType.LocalTimeType      => scala.Right(time)
          case StandardType.LocalDateTimeType  => scala.Right(timestamp)
          case StandardType.OffsetTimeType     => scala.Right(timetz)
          case StandardType.OffsetDateTimeType => scala.Right(timestamptz)
          case StandardType.ZonedDateTimeType  =>
            scala.Right(timestamptz.imap(_.toZonedDateTime)(_.toOffsetDateTime))
          case _                               => scala.Left(s"Unsupported primitive type: $standardType")

    private def recordCodec[Z](fields: Chunk[Schema.Field[Z, ?]]): scala.Either[String, Codec[?]] =
      // For now, return a simple JSONB codec or Left
      // Full implementation would need complex tuple handling
      scala.Left("Record codecs not yet fully implemented - use manual codec construction")

    private def enumCodec[Z](enumSchema: Schema.Enum[Z]): scala.Either[String, Codec[?]] =
      // Simplified enum handling
      scala.Left("Enum codecs not yet fully implemented - use manual codec construction")

    /** Generates PostgreSQL DDL type for a schema */
    def toSqlType[A](schema: Schema[A]): scala.Either[String, String] =
      toSqlTypeImpl(schema)

    private def toSqlTypeImpl[A](schema: Schema[A]): scala.Either[String, String] =
      schema match
          case primitive: Schema.Primitive[?] =>
            primitiveSqlType(primitive.standardType)

          case optional: Schema.Optional[?] =>
            toSqlTypeImpl(optional.schema) // Nullability is handled at column level

          case _: Schema.Sequence[?, ?, ?] =>
            scala.Right("JSONB") // Arrays can be JSONB or specific array types

          case _: Schema.Record[?] =>
            scala.Right("JSONB") // Complex types as JSONB

          case transform: Schema.Transform[?, ?, ?] =>
            toSqlTypeImpl(transform.schema)

          case lazySchema: Schema.Lazy[?] =>
            toSqlTypeImpl(lazySchema.schema)

          case _ =>
            scala.Right("JSONB") // Default to JSONB for complex types

    private def primitiveSqlType(standardType: StandardType[?]): scala.Either[String, String] =
      standardType match
          case StandardType.StringType         => scala.Right("TEXT")
          case StandardType.BoolType           => scala.Right("BOOLEAN")
          case StandardType.ShortType          => scala.Right("SMALLINT")
          case StandardType.IntType            => scala.Right("INTEGER")
          case StandardType.LongType           => scala.Right("BIGINT")
          case StandardType.FloatType          => scala.Right("REAL")
          case StandardType.DoubleType         => scala.Right("DOUBLE PRECISION")
          case StandardType.BinaryType         => scala.Right("BYTEA")
          case StandardType.CharType           => scala.Right("CHAR(1)")
          case StandardType.UUIDType           => scala.Right("UUID")
          case StandardType.BigDecimalType     => scala.Right("NUMERIC")
          case StandardType.BigIntegerType     => scala.Right("NUMERIC")
          case StandardType.LocalDateType      => scala.Right("DATE")
          case StandardType.LocalTimeType      => scala.Right("TIME")
          case StandardType.LocalDateTimeType  => scala.Right("TIMESTAMP")
          case StandardType.InstantType        => scala.Right("TIMESTAMPTZ")
          case StandardType.OffsetTimeType     => scala.Right("TIMETZ")
          case StandardType.OffsetDateTimeType => scala.Right("TIMESTAMPTZ")
          case StandardType.ZonedDateTimeType  => scala.Right("TIMESTAMPTZ")
          case StandardType.DurationType       => scala.Right("INTERVAL")
          case _                               => scala.Right("TEXT")
end SchemaCodec
