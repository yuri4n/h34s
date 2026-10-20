package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.internal.BodyDecoding
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import java.nio.charset.StandardCharsets
import scala.scalajs.js

/** A secret value from configuration, such as an API token.
  *
  * `toString` never shows the value, and `matches` compares in constant time for a given
  * candidate length, so a timing attack cannot learn the secret one character at a time.
  */
final class Secret private (value: String):
    /** The plain value. Call it only where the value must leave the program, such as a client. */
    def reveal: String = value

    /** Compares a candidate with the secret without returning early on the first difference. */
    def matches(candidate: String): Boolean =
      val expected = value.getBytes(StandardCharsets.UTF_8)
      val actual   = candidate.getBytes(StandardCharsets.UTF_8)
      if expected.isEmpty then actual.isEmpty
      else
          var difference = expected.length ^ actual.length
          var index      = 0
          while index < actual.length do
              difference |= expected(index % expected.length) ^ actual(index)
              index += 1
          difference == 0

    override def toString: String = "Secret(<redacted>)"

    override def equals(other: Any): Boolean =
      other.isInstanceOf[Secret] && other.asInstanceOf[Secret].matches(value)

    override def hashCode: Int = Secret.hashSeed

object Secret:
    // Equal secrets must share a hash code; a constant avoids deriving one from the value.
    private val hashSeed = 0x5ec2e7

    def apply(value: String): Secret = new Secret(value)

    /** Decodes a JavaScript string.
      *
      * Any other value fails with a native-converter shape error whose message keeps only the
      * expected type, the JavaScript type and the path: the value and the input document are
      * replaced by `<redacted>`. h34s body and config decoders map it to 422 and `ConfigError` as
      * usual. A shape error of ANOTHER field still prints the whole input document (native-converter
      * behavior), secrets included, if your code logs it.
      */
    given NativeConverter[Secret]:
        extension (secret: Secret) def toNative: js.Any = secret.reveal

        def fromNative(state: ParseState): Secret =
          if js.typeOf(state.json) == "string" then Secret(state.json.asInstanceOf[String])
          else throw redactedShapeError(state)

    private val expected = "a string secret"

    /** native-converter's own message embeds the value and the whole document. Keep its format
      * (so `BodyDecoding` still recognizes it) with both replaced.
      */
    private def redactedShapeError(state: ParseState): RuntimeException =
      val path =
        // `undefined` cannot go through `ParseState.fail` (JSON.stringify(undefined) is undefined
        // behavior); it only happens at the root.
        if js.isUndefined(state.json) then "root"
        else
            try state.fail[String](expected)
            catch case error: RuntimeException => BodyDecoding.shapeErrorPath(error).getOrElse("root")
      RuntimeException(
        s"\nJson parse error. Expected to be $expected, instead was ${js.typeOf(state.json)}:\n<redacted>\n\nAt position:\n$path\n\nIn:\n<redacted>\n",
      )
