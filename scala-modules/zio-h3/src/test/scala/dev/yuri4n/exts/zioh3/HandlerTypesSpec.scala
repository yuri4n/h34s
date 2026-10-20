package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.internal.HandlerMetadata
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.scalajs.js

/** The response types that `ZHandler.define` records for the generated route declaration. */
object HandlerTypesSpec extends ZIOSpecDefault:
    private def record(handler: EventHandler): js.Dynamic = HandlerMetadata.read(handler.toNative).get

    private def success(handler: EventHandler): String =
      record(handler).types.asInstanceOf[js.Function0[js.Dynamic]]().success.asInstanceOf[String]

    def spec = suite("ZHandler.define types")(
      test("value bodies, Unit and streams, with and without options") {
        assertTrue(
          success(ZHandler.define(_ => ZIO.succeed(Map("a" -> 1)))) == "{ data: Record<string, number> }",
          success(ZHandler.define(_ => ZIO.unit)) == "undefined",
          success(ZHandler.define(_ => ZIO.succeed(ZStream("a", "b")))) == "string",
          success(ZHandler.define(HandlerOptions(success = SuccessEnvelope.Raw))(_ => ZIO.succeed(true))) == "boolean",
        )
      },
      test("at(route) mounts the ZIO handler and keeps its types") {
        val handler = ZHandler.define(_ => ZIO.succeed(List("a"))).at(Route.delete("/api/notes/:id"))
        val mounted = record(handler)
        assertTrue(
          mounted.route.asInstanceOf[String] == "/api/notes/:id",
          mounted.method.asInstanceOf[String] == "delete",
          success(handler) == "{ data: Array<string> }",
        )
      },
    )
