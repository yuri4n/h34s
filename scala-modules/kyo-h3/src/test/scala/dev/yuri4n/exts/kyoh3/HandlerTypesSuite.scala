package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.HandlerMetadata
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

/** The response types that `KHandler.define` records for the generated route declaration. */
class HandlerTypesSuite extends FunSuite:
    final case class Point(x: Int, y: Int) derives NativeConverter

    private val entry = "{ message: string; path?: string; debug?: { cause: string } }"

    private def record(handler: EventHandler): js.Dynamic =
      HandlerMetadata.read(handler.toNative).getOrElse(fail("no h34s record"))

    /** The `success` and `errors` TypeScript of a handler, read as `h34s-scala` reads them. */
    private def types(handler: EventHandler): (String, String) =
      val types = record(handler).types.asInstanceOf[js.Function0[js.Dynamic]]()
      (types.success.asInstanceOf[String], types.errors.asInstanceOf[String])

    test("value bodies in the data envelope, typed from the converter in use") {
      assertEquals(types(KHandler.define(_ => List(1L, 2L))), ("{ data: Array<string> }", s"{ errors: Array<$entry> }"))
      assertEquals(types(KHandler.define(_ => Option("a")))._1, "{ data: string | null }")
      assertEquals(types(KHandler.define(_ => Point(1, 2)))._1, "{ data: unknown }")
    }

    test("Raw sends the value as is; SingleOrList errors are one entry or a list") {
      val options = HandlerOptions(success = SuccessEnvelope.Raw, errors = ErrorEnvelope.SingleOrList)
      assertEquals(types(KHandler.define(options)(_ => "text")), ("string", s"{ error: $entry } | { errors: Array<$entry> }"))
    }

    // claude 2026-10-20: changed: a stream is sent with Response.sendStream, and its Sent body is text (ADR-0004)
    test("Unit is a response without a body; a sent stream of strings is text") {
      assertEquals(types(KHandler.define(_ => ()))._1, "undefined")
      assertEquals(types(KHandler.define(_ => Response.sendStream(Stream.init(Seq("a", "b")))))._1, "string")
    }

    test("the record is built only when read, and at(route) keeps it") {
      var described = 0
      given NativeConverter[Point] = new NativeConverter[Point] with WireType.Described:
          def wireType: WireType =
            described += 1
            WireType.ObjectOf(List(WireField("x", WireType.NumberValue), WireField("y", WireType.NumberValue)))
          extension (point: Point) def toNative: js.Any = js.Dynamic.literal(x = point.x, y = point.y)
          def fromNative(state: org.getshaka.nativeconverter.ParseState): Point = Point(0, 0)
      val handler = KHandler.define(_ => Point(1, 2)).at(Route.get("/api/point"))
      assertEquals(described, 0)
      assertEquals(types(handler)._1, "{ data: { x: number; y: number } }")
      assertEquals(described, 1)
      val mounted = record(handler)
      assertEquals(List(mounted.route, mounted.method).map(_.asInstanceOf[String]), List("/api/point", "get"))
    }
end HandlerTypesSuite
