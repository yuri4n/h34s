package dev.yuri4n.exts.h3

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

    /** Decodes a JavaScript string. The error message never contains the value. */
    given NativeConverter[Secret]:
        extension (secret: Secret) def toNative: js.Any = secret.reveal

        def fromNative(state: ParseState): Secret =
          if js.typeOf(state.json) == "string" then Secret(state.json.asInstanceOf[String])
          else state.fail("a string secret")
