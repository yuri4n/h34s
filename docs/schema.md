# Schemas: one derivation, many formats

Schemas are optional. `NativeConverter` is the type class every h34s API asks for, and a plain `derives NativeConverter` is a complete path. Add a schema when you want validation in the model, or JSON and YAML from the same definition.

With a schema, define each model once:

```scala
final case class Greeting(message: String) derives Schema, NativeSchema
```

That one derivation gives the native value h34s sends and reads, JSON text, YAML text (Kyo only), and validation that runs on every request body read.

**Status.** Both bridges are part of `0.2.0-SNAPSHOT` (not published yet). The published `0.1.0-alpha.1` had a different ZIO schema reader; see the [migration guide](migration.md).

## Choose a bridge

| | Kyo | ZIO |
| --- | --- | --- |
| Module | `native-converter-kyo-schema-ext` | `native-converter-zio-schema-ext` |
| Package | `dev.yuri4n.exts.ntcvkyoschema` | `dev.yuri4n.exts.ntcvzioschema` |
| Derive | `derives Schema, NativeSchema` | `derives Schema, NativeSchema` (needs `import zio.schema.derived`) |
| JSON | kyo-schema-json: `Json.encode`, `Json.decode` | zio-schema-json: `JsonCodec` |
| YAML | kyo-schema-yaml: `encodeString[Yaml]`, `decodeString[Yaml]` | Not on Scala.js: zio-json-yaml is published for the JVM only |
| Validation | `Schema[A].check(...)` and friends | `Validation` in the schema (`.validation`, `@validate`) |
| Messages | Your check messages | Built from the rule and its bounds |

The bridges depend on the native layer (`h3-ext`) and their schema library only. Neither installs a blanket instance: a type gets a `NativeSchema` only when it derives one or calls `NativeSchema[A]` (Kyo) or `NativeSchema.derived` (ZIO). A type can keep a separately derived `NativeConverter`; the two do not conflict.

Add kyo-schema-json and kyo-schema-yaml (Kyo 1.0.0-RC7) to your application yourself when you need JSON or YAML text. The bridge does not depend on them.

## Kyo: derive, validate, use

Derive both at once for a model without checks. For a model with checks, declare the schema in the companion and derive only `NativeSchema`; kyo-schema has no check annotations, so this keeps one schema. This is the demo's `Greetings.scala`:

```scala
import dev.yuri4n.exts.ntcvkyoschema.NativeSchema
import kyo.*

/** The request model. Its one schema carries the validation; `NativeSchema` derives from it, so
  * `request.body.json[GreetingRequest]` answers 422 with these messages and the `name` path.
  */
final case class GreetingRequest(name: String) derives NativeSchema

object GreetingRequest:
    given Schema[GreetingRequest] = Schema[GreetingRequest]
      .check(_.name)(_.trim.nonEmpty, "must not be blank")
      .checkMaxLength(_.name)(80)

final case class Greeting(message: String) derives Schema, NativeSchema
```

The handler asks for the validated model and repeats no checks:

```scala
for
  input    <- request.body.json[GreetingRequest]
  greeting <- Env.use[Greetings](_.greet(input.name))
yield greeting
```

A blank name gets a 422 with `{"errors":[{"message":"must not be blank","path":"name"}]}`. An 81-character name gets `{"errors":[{"message":"length must be <= 80","path":"name"}]}`.

The same schema gives JSON and YAML text:

```scala
val json: String = Json.encode(greeting)
val back = Json.decode[Greeting](json)                         // a Result
val yaml: String = summon[Schema[Greeting]].encodeString[Yaml](greeting)
```

### Kyo wire shapes

The native value is what `JSON.parse(Json.encode(x))` would give, built without JSON text:

| Scala | Native value |
| --- | --- |
| `None` | The key is absent. On input, absent, `null` and `undefined` all decode to `None` |
| Enum case, sealed trait case | A single-key object: `{"Red":{}}`, `{"Circle":{"radius":1.5}}`. `@discriminator("type")` gives `{"type":"Joined",...}` |
| `Map[String, A]` | An object |
| `Map[Int, A]` and other keys | An array of `{"key":...,"value":...}` |
| `BigDecimal`, `BigInt` | Strings. Decoding rejects (generic 422) a string longer than 1000 characters and a `BigDecimal` exponent beyond +/-10000, before parsing: Scala.js parses big numbers in quadratic time |
| `Instant`, `Duration` | ISO strings |
| Bytes (`Span[Byte]`) | A base64 string |
| `Double` NaN and infinities | The strings `"NaN"`, `"Infinity"`, `"-Infinity"` |
| `Long` | A number; decoding accepts only safe integers (up to 2^53 - 1) |

Write your TypeScript client types against these shapes.

### Kyo validation rules

- Only the root schema's checks run, like `Schema.validate`. Put checks on the root with focus paths, such as `_.customer.name`. A nested type's own checks do not run.
- Each failed check is one entry: your message, and the field path when the check is on a field (`customer.name`). A check on the whole value has no path.
- A value with the wrong shape (missing field, wrong type, out-of-range integer, a non-plain object, invalid ISO or base64 text) is one generic entry: `Request body does not match the expected shape`.
- An exception from a schema transform is a defect (opaque 500), never blamed on the input.
- A fluent `Schema.rename`, `renameAllFields` or `.discriminator`, and adjacent, tuple or untagged unions, are converted generically; strict number checks then do not apply to those values.

## ZIO: derive, validate, use

Import `zio.schema.derived` for `derives Schema`. Put normalization and validation in the schema. This is the demo's `Notes.scala`:

```scala
import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import zio.schema.{DeriveSchema, Schema, StandardType, derived}
import zio.schema.validation.Validation

/** The JSON that the Notes routes send. One derivation: the ZIO Schema and the native converter. */
final case class NoteView(id: Int, text: String) derives Schema, NativeSchema

/** The JSON that `POST /api/notes` reads. */
final case class CreateNote(text: String) derives NativeSchema

object CreateNote:
    /** Trims the text, then requires 1-2000 characters. The native converter uses this schema. */
    given Schema[CreateNote] =
      given Schema[String] = Schema.Primitive(StandardType.StringType)
        .transform(_.trim, identity)
        .validation(Validation.minLength(1) && Validation.maxLength(2000))
      DeriveSchema.gen[CreateNote]
```

`request.body.json[CreateNote]` trims the text and checks its length. A blank text gets a 422 with `{"errors":[{"message":"must have at least 1 character","path":"text"}]}`.

ZIO Schema's own validation messages quote the input, so the bridge builds messages from the rule and its bounds instead, such as `must have at least 3 characters` or `must be greater than 17`. Paths look like `lead.name` and `members[1].name`. A negated rule (`!`) is reported with the wording of the rule it negates; this is a zio-schema limitation.

The ZIO bridge supports these shapes and fails with `UnsupportedSchema` when the converter is created for any other:

| Schema form | Native value |
| --- | --- |
| `String`, `Boolean` | Strict primitives |
| `Int` | An integer in the 32-bit range |
| `Double` | A finite number |
| Records (case classes) | Plain objects; field names, aliases, defaults, transient fields and `rejectExtraFields` are respected |
| `Option` | Absent or `null` is `None` |
| Sequences | Arrays |
| Lazy and transformed schemas | Through their underlying schema |

Enums, sealed traits, maps, sets, tuples, `Either` and other primitives are not supported by the ZIO bridge yet.

`NativeSchema(schema)` also returns an explicit `Codec` with `decode`, `validate` and `encode` for code that holds a native value. Ordinary handlers do not need it.

## Secrets in a schema type

`Secret` has a `NativeConverter`, but no schema library knows it. Each bridge provides a `Schema[Secret]` that is a string on the wire: import `dev.yuri4n.exts.ntcvkyoschema.secretSchema` (Kyo) or `dev.yuri4n.exts.ntcvzioschema.secretSchema` (ZIO) where you derive a schema that holds a `Secret`, such as a config model. A wrong value type is the generic 422 (or a `ConfigError` for runtime config), without the value. JSON or YAML that you write with this schema contains the plain value, so do not encode a secret into a response or a log. See [configuration](configuration.md).

## Request errors

| Problem | Status | Entries |
| --- | --- | --- |
| Body is not valid JSON | 400 | `Request body is not valid JSON` |
| Wrong shape | 422 | One generic entry, no input values |
| Failed checks | 422 | One entry per check, with `path` when known |
| Exception in a transform, validator or converter | 500 | Opaque; diagnostics only in development or debug mode |

Do not catch every `Throwable` and turn it into a `SafeError`. That would show programming errors to clients as if they were input problems.

## Keep a NativeConverter-only path

You never need a schema. `derives NativeConverter` works with every h34s API: body reads, responses, configuration, storage and streams. Shape errors are still a generic 422. A derived `NativeConverter` does not validate domain rules, and version 0.9 accepts a missing or `null` value for a `String` field; check such rules in your code.

## Dependencies and compatibility

| Module | Schema dependencies |
| --- | --- |
| `native-converter-kyo-schema-ext` | kyo-schema 1.0.0-RC7. Add kyo-schema-json and kyo-schema-yaml yourself for text formats |
| `native-converter-zio-schema-ext` | zio-schema 1.7.5. Add zio-schema-derivation 1.7.5 for `derives Schema`, and zio-schema-json for JSON text |
| `kyo-h3-ext`, `zio-h3-ext` | None. The handler modules never bring a schema library |

Both bridges use Scala 3.9.0 and NativeConverter 0.9.0. See [compatibility](compatibility.md) for the tested profile.

What is tested:

- Kyo bridge (`NativeSchemaSuite`, 25 tests with `H3BodySuite`): wire equality with kyo-schema-json for records, options, enums, sealed traits, collections, numeric edges, big numbers (and their length and exponent limits), times and bytes; strict decoding; validation messages and paths; no input values in messages; no JSON text round trip; no blanket instance; JSON and YAML round trips from the same schema; `secretSchema`; HTTP 422 entries.
- ZIO bridge (`NativeSchemaSpec`, 20 tests, with `secretSchema`) and ZIO request bodies (`RequestBodySpec`): supported shapes, transforms, accumulated entries with paths, and the 400/422 statuses.
- The demo's Kyo routes answered 422 with `must not be blank` and `length must be <= 80` over an h3 server; see [compatibility](compatibility.md).
