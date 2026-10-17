package dev.yuri4n.exts.ntcvkyoschema

import kyo.Schema
import kyo.schema.{alias, discriminator, rename}
import org.getshaka.nativeconverter.NativeConverter

import java.time.{Duration, Instant}

/** Test models. Each one has ONE schema; `NativeSchema` derives from it. */
object Models:
    final case class Address(street: String, zip: Option[String]) derives Schema

    final case class Person(
        name: String,
        age: Int,
        address: Address,
        tags: List[String],
        nickname: Option[String],
    ) derives Schema, NativeSchema

    enum Color derives Schema, NativeSchema:
        case Red, Green, Blue

    sealed trait Shape derives Schema, NativeSchema
    object Shape:
        final case class Circle(radius: Double)              extends Shape
        final case class Rect(width: Double, height: Double) extends Shape
        case object Empty                                    extends Shape

    final case class Drawing(title: String, shapes: List[Shape], color: Color, background: Option[Color])
        derives Schema, NativeSchema

    final case class Numbers(int: Int, long: Long, double: Double, float: Float, short: Short, byte: Byte)
        derives Schema, NativeSchema

    final case class Bigs(decimal: BigDecimal, integer: BigInt) derives Schema, NativeSchema

    final case class Times(at: Instant, took: Duration) derives Schema, NativeSchema

    final case class Collections(
        list: List[Int],
        vector: Vector[String],
        set: Set[Int],
        byName: Map[String, Int],
        byId: Map[Int, String],
    ) derives Schema, NativeSchema

    final case class Blob(name: String, data: kyo.Span[Byte]) derives Schema, NativeSchema

    /** Wire names from annotations: the shape hint still finds these fields. */
    final case class Renamed(@rename("full_name") name: String, @alias("years") age: Int) derives Schema, NativeSchema

    @discriminator("type")
    sealed trait Event derives Schema, NativeSchema
    object Event:
        final case class Joined(user: String, seat: Int) extends Event
        final case class Left(user: String)              extends Event

    /** Checks are values on the schema, so a checked model declares its schema once in its companion. */
    final case class Signup(name: String, email: String, age: Int) derives NativeSchema
    object Signup:
        given Schema[Signup] = Schema[Signup]
          .check(_.name)(_.trim.nonEmpty, "must not be blank")
          .checkMaxLength(_.name)(20)
          .check(_.age)(_ >= 18, "must be at least 18")
          .check(signup => signup.email.contains("@"), "email must contain @")

    final case class Customer(name: String) derives Schema
    final case class Order(customer: Customer, items: List[String]) derives NativeSchema
    object Order:
        given Schema[Order] = Schema[Order]
          .check(_.customer.name)(_.nonEmpty, "customer name is required")
          .checkMinItems(_.items)(1)

    /** Unknown fields are ignored unless the schema denies them. */
    final case class Strict(name: String) derives NativeSchema
    object Strict:
        given Schema[Strict] = Schema[Strict].denyUnknownFields

    /** A transform that throws: a defect, never blamed on the input. */
    final case class Port(value: Int) derives NativeSchema
    object Port:
        given Schema[Port] =
          Schema.intSchema.transform[Port](value => if value < 0 then throw IllegalStateException("private defect") else Port(value))(_.value)

    /** A constructor that rejects: a decode failure, so an invalid input. */
    sealed abstract case class Even private (value: Int) derives NativeSchema
    object Even:
        def make(value: Int): Either[String, Even] = if value % 2 == 0 then Right(new Even(value) {}) else Left("odd")
        given Schema[Even] = Schema.derivedVia(make)

    /** Keeps its own derived NativeConverter; a NativeSchema can still be built explicitly. */
    final case class Message(text: String) derives NativeConverter
    object Message:
        given Schema[Message] = Schema.derived
end Models
