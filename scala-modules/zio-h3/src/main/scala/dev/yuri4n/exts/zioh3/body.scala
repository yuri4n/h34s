package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.internal.{BodyDecoding, BodyReadFailure}
import org.getshaka.nativeconverter.NativeConverter
import zio.{IO, ZIO}

extension (body: RequestBody)
    /** Decodes a JSON body with the handler's limit (`HandlerOptions.bodyLimit`, 1 MiB unless you
      * change it). See the overload with an explicit limit.
      */
    def json[A: NativeConverter]: IO[HttpError | InvalidInput, A] =
      body.json[A](body.defaultLimit)

    // claude 2026-10-19: changed: a missing content type is checked after the read, so a request without a body needs none (issue #41)
    /** Decodes a JSON body with a `NativeConverter`.
      *
      *   - 415 when the content type is not JSON, or when a body is present without a content type
      *     and `HandlerOptions.requireJsonContentType` is on (the default);
      *   - 413 when the body exceeds `limit` (checked from `Content-Length`, then while streaming);
      *   - 400 when the text is not valid JSON;
      *   - `InvalidInput` (422) when the value does not match the converter's shape, or when a
      *     schema-backed converter (`derives NativeSchema`) rejects it.
      *
      * Any other exception from the converter, also a thrown `SafeError`, is a defect (opaque 500).
      */
    def json[A: NativeConverter](limit: BodyLimit): IO[HttpError | InvalidInput, A] =
      if !BodyDecoding.acceptsJson(body.contentType, requireContentType = false) then ZIO.fail(BodyDecoding.unsupportedMediaType)
      else
          readText(body, limit).flatMap { text =>
            if !BodyDecoding.acceptsJsonBody(body.contentType, body.requireJsonContentType, text) then
                ZIO.fail(BodyDecoding.unsupportedMediaType)
            else
                ZIO.fromEither(BodyDecoding.parseJson(text)).flatMap { native =>
                  ZIO.attempt(BodyDecoding.decode[A](native)).orDie.flatMap(ZIO.fromEither(_))
                }
          }

    /** The body as UTF-8 text; an absent body is the empty string. 413 above `limit`, which is the
      * handler's `HandlerOptions.bodyLimit` unless you pass one.
      */
    def text(limit: BodyLimit = body.defaultLimit): IO[HttpError, String] =
      readText(body, limit).map(_.getOrElse(""))
end extension

extension (request: Request)
    /** A required router parameter; a 400 `HttpError` when it is absent. */
    def param(name: String): IO[HttpError, String] =
      ZIO.fromOption(request.routerParam(name)).orElseFail(HttpError.badRequest(s"Missing router parameter: $name"))

/** Reads the one-shot body once; a transport failure while reading is a defect. */
private def readText(body: RequestBody, limit: BodyLimit): IO[HttpError, Option[String]] =
  ZIO.async[Any, HttpError, Option[String]] { complete =>
    body.readText(limit) {
      case Right(text)                          => complete(ZIO.succeed(text))
      case Left(BodyReadFailure.TooLarge)       => complete(ZIO.fail(BodyDecoding.tooLarge))
      case Left(BodyReadFailure.Failed(cause)) => complete(ZIO.die(cause))
    }
  }
