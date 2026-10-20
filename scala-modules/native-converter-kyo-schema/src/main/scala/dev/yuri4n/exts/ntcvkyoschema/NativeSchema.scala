package dev.yuri4n.exts.ntcvkyoschema

import dev.yuri4n.exts.h3.{ErrorEntry, InvalidInput, Secret, WireType}
import dev.yuri4n.exts.ntcvkyoschema.internal.{NativeTypes, NativeValues}
import kyo.{Frame, Schema, Structure, ValidationFailedException}
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.scalajs.js

// claude 2026-10-20: changed: the Scaladoc names the models (ADR-0014)
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
  *
  * The converter also knows the TypeScript type of what it writes (`WireType.Described`), so h34s
  * types the response of a route that returns it. Records are objects with the field names the
  * encoder writes (`@rename` included), optional fields may be absent or `null`, collections are
  * arrays, string-keyed maps are records, `Long` and the other integers are numbers, `Float` and
  * `Double` are numbers or the strings `"NaN"`, `"Infinity"`, `"-Infinity"`, big numbers,
  * instants, durations and bytes are strings, and sealed traits and enums are one-key objects (or
  * objects with the `@discriminator` field). The type is checked against the encoder with sample
  * values; a schema whose output the structure does not show (fluent `rename`, `drop`, `add`,
  * omit policies, other union representations) gets `unknown`, never a wrong type.
  *
  * Records, sealed traits and enums are named models (`WireType.Named`) after their Scala type:
  * `h34s-scala` declares each one once, as `export type Note = ...` in `h34s-models.d.ts`, and the
  * application imports it from `#h34s/models`. A generic instantiation such as `Page[Note]` is
  * written out where it is used, with `Note` still named inside.
  */
trait NativeSchema[A] extends NativeConverter[A] with WireType.Described

/** A Kyo `Schema` for h34s `Secret`: a string on the wire.
  *
  * Import it where you derive a schema that holds a secret, such as a config model:
  * `import dev.yuri4n.exts.ntcvkyoschema.secretSchema`. Decoding never puts the value in an error.
  * JSON or YAML that you write with this schema contains the plain value, so do not encode a
  * secret into a response or a log.
  */
given secretSchema: Schema[Secret] = summon[Schema[String]].transform(Secret(_))(_.reveal)

object NativeSchema:
    /** The instance for `derives NativeSchema`. It uses the type's `Schema`, with its checks. */
    def derived[A](using schema: Schema[A], frame: Frame): NativeSchema[A] = FromSchema(schema)

    /** A converter from the `Schema` in scope, for a type you cannot annotate. */
    def apply[A](using schema: Schema[A], frame: Frame): NativeSchema[A] = FromSchema(schema)

    private final class FromSchema[A](schema: Schema[A])(using Frame) extends NativeSchema[A]:
        private given Schema[A] = schema

        // The structure is only a shape hint; build it on first use, not when the given is created.
        private lazy val shape: Option[Structure.Type] = Some(schema.structure)

        /** Built when a route declaration is written, never while a request runs. */
        lazy val wireType: WireType = NativeTypes.describe(schema, roundTrip)

        /** Reads a sample with the schema, without its checks, and writes it again. */
        private def roundTrip(native: js.Any): Option[js.Any] =
          NativeTypes.attempt(NativeValues.read(native, shape))
            .flatMap(tree => Structure.decode[A](tree).fold(Some(_), _ => None, _ => None))
            .flatMap(value => NativeTypes.attempt(value.toNative))

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
