package dev.yuri4n.exts.h3.internal

import dev.yuri4n.exts.h3.{HttpError, InvalidInput}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

/** The typed-body policy shared by every runtime. Pure apart from `JSON.parse`. */
private[exts] object BodyDecoding:
    val tooLarge: HttpError             = HttpError(413, "Request body is too large")
    val unsupportedMediaType: HttpError = HttpError(415, "Request body must be JSON")
    val invalidJson: HttpError          = HttpError(400, "Request body is not valid JSON")

    /** JSON is accepted when the content type is absent, `application/json` or a `+json` application subtype. */
    def acceptsJson(contentType: Option[String]): Boolean =
      contentType.map(_.split(';').head.trim.toLowerCase).forall { mediaType =>
        mediaType.isEmpty || mediaType == "application/json" ||
        (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
      }

    /** Strict `JSON.parse`. A missing body becomes native `undefined`. `__proto__` keys are dropped. */
    def parseJson(text: Option[String]): Either[HttpError, js.Any] =
      text match
          case None                         => Right(js.undefined)
          case Some(value) if value.isEmpty => Right(js.undefined)
          case Some(value)                  =>
            try Right(js.JSON.parse(value, dropPrototypeKeys))
            catch case _: js.JavaScriptException => Left(invalidJson)

    private val dropPrototypeKeys: js.Function2[js.Any, js.Any, js.Any] =
      (key, value) => if js.typeOf(key) == "string" && key.asInstanceOf[String] == "__proto__" then js.undefined else value

    /** Runs the converter. Shape failures reported by native-converter (`ParseState.fail`) and
      * `InvalidInput` thrown by a schema-backed converter become `InvalidInput`. Any other
      * exception is a defect and is rethrown.
      */
    def decode[A](value: js.Any)(using converter: NativeConverter[A]): Either[InvalidInput, A] =
      try Right(converter.fromNative(value))
      catch
          case invalid: InvalidInput                    => Left(invalid)
          case error: RuntimeException if isShapeError(error) => Left(InvalidInput())

    /** native-converter 0.9 reports shape errors as a plain `RuntimeException` from `ParseState.fail`. */
    def isShapeError(error: Throwable): Boolean =
      error.getClass == classOf[RuntimeException] &&
      Option(error.getMessage).exists(_.startsWith(ShapeErrorPrefix))

    /** The JSON path of a native-converter shape error (`root.database.url`), never the value. */
    def shapeErrorPath(error: Throwable): Option[String] =
      if !isShapeError(error) then None
      else
          val lines = error.getMessage.linesIterator.toList
          lines.dropWhile(_ != "At position:").drop(1).headOption.map(_.trim).filter(_.startsWith("root"))

    private val ShapeErrorPrefix = "\nJson parse error."
end BodyDecoding
