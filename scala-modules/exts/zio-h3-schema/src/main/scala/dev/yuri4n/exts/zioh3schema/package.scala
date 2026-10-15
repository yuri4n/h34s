package dev.yuri4n.exts.zioh3schema

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.exts.zioh3.{SafeError, TaskHandler, ZHandler}
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.ZIO
import zio.schema.Schema

import scala.scalajs.js

/** Expected request rejection. Raw decoder/validation messages are never public response data. */
final case class InvalidBody() extends SafeError:
    val message = "Request body does not match schema"
    override def status = 422

private given NativeConverter[js.Any] with
    extension (value: js.Any) def toNative: js.Any = value
    def fromNative(state: ParseState): js.Any = state.json

private def invalidJSON(error: Any): Boolean =
  val native = error.asInstanceOf[js.Any]
  if native == null || js.typeOf(native) != "object" then false
  else
    val status = native.asInstanceOf[js.Dynamic].statusCode.asInstanceOf[js.Any]
    val message = native.asInstanceOf[js.Dynamic].message.asInstanceOf[js.Any]
    js.typeOf(status) == "number" && status.asInstanceOf[Double] == 400 &&
      js.typeOf(message) == "string" && message.asInstanceOf[String] == "Invalid JSON body"

extension (handler: ZHandler.type)
  /** Opt in per call; NativeConverter-only readBody is unchanged. */
  def readBodyWithSchema[A](using schema: Schema[A]): TaskHandler[A] =
    for
      codec <- ZIO.attempt(NativeSchema(schema))
      native <- handler.readBody[js.Any](strict = true).mapError {
        case js.JavaScriptException(error) if invalidJSON(error) => InvalidBody()
        case other => other
      }
      result <- ZIO.attempt(codec.decode(native))
      value <- ZIO.fromEither(result.left.map(_ => InvalidBody()))
    yield value
