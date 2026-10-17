package dev.yuri4n.exts.ntcvkyoschema

import dev.yuri4n.exts.h3.{ErrorEntry, InvalidInput}
import dev.yuri4n.exts.ntcvkyoschema.internal.NativeValues
import kyo.{Frame, Schema, Structure, ValidationFailedException}
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.scalajs.js

/** A `NativeConverter` derived from a Kyo `Schema`. Opt in per type with `derives Schema, NativeSchema`.
  *
  * One schema then gives the native value for h34s, JSON (kyo-schema-json) and YAML
  * (kyo-schema-yaml). The native value has the shape kyo-schema-json writes: `x.toNative` is equal
  * to `JSON.parse(Json.encode(x))`, and no JSON text is made on the way.
  *
  * Decoding reads the value with the schema and then runs the schema's checks (`Schema.validate`):
  *
  *   - a value that does not have the schema's shape throws `InvalidInput()`: a 422 with the generic
  *     message;
  *   - failed checks throw `InvalidInput` with one entry per check: the check's message and, for a
  *     field check, the field path (`address.city`). Messages come from your schema, never from the
  *     input;
  *   - any other exception, for example from a schema transform that throws, is a defect and is
  *     rethrown (an opaque 500 in a handler).
  *
  * There is no blanket instance: only types that derive it, or that call `NativeSchema[A]`, get one.
  * A type can keep a separately derived `NativeConverter`; the two do not conflict.
  */
trait NativeSchema[A] extends NativeConverter[A]

object NativeSchema:
    /** The instance for `derives NativeSchema`. It uses the type's `Schema`, with its checks. */
    def derived[A](using schema: Schema[A], frame: Frame): NativeSchema[A] = FromSchema(schema)

    /** A converter from the `Schema` in scope, for a type you cannot annotate. */
    def apply[A](using schema: Schema[A], frame: Frame): NativeSchema[A] = FromSchema(schema)

    private final class FromSchema[A](schema: Schema[A])(using Frame) extends NativeSchema[A]:
        private given Schema[A] = schema

        // The structure is only a shape hint; build it on first use, not when the given is created.
        private lazy val shape: Option[Structure.Type] = Some(schema.structure)

        extension (value: A) def toNative: js.Any = NativeValues.write(Structure.encode(value), shape)

        def fromNative(state: ParseState): A =
          val tree =
            try NativeValues.read(state.json, shape)
            catch case _: NativeValues.Malformed => throw InvalidInput()
          Structure.decode[A](tree).fold(validated, _ => throw InvalidInput(), cause => throw cause)

        private def validated(value: A): A =
          val failures = schema.validate(value)
          if failures.isEmpty then value else throw InvalidInput(failures.toList.map(entry))
    end FromSchema

    /** The check's own message, and its field path when the check is on a field. */
    private def entry(failure: ValidationFailedException): ErrorEntry =
      ErrorEntry(failure.message, Option.when(failure.path.nonEmpty)(failure.path.mkString(".")))
end NativeSchema
