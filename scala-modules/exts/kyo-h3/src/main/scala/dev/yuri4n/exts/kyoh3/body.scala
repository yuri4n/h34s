package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.{BodyDecoding, BodyReadFailure}
import dev.yuri4n.exts.kyoh3.internal.Interop
import kyo.{Abort, Async, Frame, Result, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter

extension (body: RequestBody)
    /** Decodes a JSON body with the handler's limit (`HandlerOptions.bodyLimit`, 1 MiB unless you
      * change it). See the overload with an explicit limit.
      */
    def json[A: NativeConverter](using Frame): A < (Async & Abort[HttpError | InvalidInput]) =
      body.json[A](body.defaultLimit)

    /** Decodes a JSON body with a `NativeConverter`.
      *
      *   - 415 when the content type is neither absent nor JSON;
      *   - 413 when the body exceeds `limit` (checked from `Content-Length`, then while streaming);
      *   - 400 when the text is not valid JSON;
      *   - `InvalidInput` (422) when the value does not match the converter's shape.
      *
      * Any other exception from the converter is a defect (opaque 500).
      */
    def json[A: NativeConverter](limit: BodyLimit)(using Frame): A < (Async & Abort[HttpError | InvalidInput]) =
      if !BodyDecoding.acceptsJson(body.contentType) then Abort.fail(BodyDecoding.unsupportedMediaType)
      else
          readText(body, limit).map { text =>
            Abort.get(BodyDecoding.parseJson(text)).map { native =>
              Sync.defer(BodyDecoding.decode[A](native)).map(decoded => Abort.get(decoded))
            }
          }

    /** The body as UTF-8 text; an absent body is the empty string. 413 above `limit`, which is the
      * handler's `HandlerOptions.bodyLimit` unless you pass one.
      */
    def text(limit: BodyLimit = body.defaultLimit)(using Frame): String < (Async & Abort[HttpError]) =
      readText(body, limit).map(_.getOrElse(""))
end extension

extension (request: Request)
    /** A required router parameter; a 400 `HttpError` when it is absent. */
    def param(name: String)(using Frame): String < Abort[HttpError] =
      request.routerParam(name) match
          case Some(value) => value
          case None        => Abort.fail(HttpError.badRequest(s"Missing router parameter: $name"))

private def readText(body: RequestBody, limit: BodyLimit)(using Frame): Option[String] < (Async & Abort[HttpError]) =
  Interop.async[HttpError, Option[String]] { complete =>
    body.readText(limit) {
      case Right(text)                          => complete(Result.succeed(text))
      case Left(BodyReadFailure.TooLarge)       => complete(Result.fail(BodyDecoding.tooLarge))
      case Left(BodyReadFailure.Failed(cause)) => complete(Result.panic(cause))
    }
  }
