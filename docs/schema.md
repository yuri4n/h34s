# Optional schema validation

Schema support is optional. `ZHandler.readBody[A: NativeConverter]` remains the complete NativeConverter-only entry point. It has no schema requirement, and its conversion/error behavior is unchanged.

Applications that want schema-driven input validation can add the optional `zioH3SchemaExt` source project. It supplies `ZHandler.readBodyWithSchema[A: Schema]`. The example opts in; the core handler library does not.

## Put validation in the model

The Notes request model defines normalization and domain rules once:

```scala
import zio.schema.{DeriveSchema, Schema, StandardType}
import zio.schema.validation.Validation

final case class CreateNote(text: String)

object CreateNote:
    given Schema[CreateNote] =
      given Schema[String] = Schema.Primitive(StandardType.StringType)
        .transform(_.trim, identity)
        .validation(Validation.minLength(1) && Validation.maxLength(2000))
      DeriveSchema.gen[CreateNote]
```

The local string schema applies only to this derivation. Structural decoding rejects a missing, null, or incorrectly typed required field before Scala string operations run. The field schema then trims the string and validates the normalized value.

The endpoint asks for the validated model:

```scala
import dev.yuri4n.exts.zioh3.ZHandler
import dev.yuri4n.exts.zioh3schema.readBodyWithSchema

val createNote = AppHandler.define:
    for
      input <- ZHandler.readBodyWithSchema[CreateNote]
      note <- ZIO.serviceWithZIO[Notes](_.create(input.text))
      _ <- ZHandler.setResponseStatus(201)
    yield note
```

`AppHandler` and `Notes` remain application-owned services. There are no repeated null, trimming, or length checks in this handler. Response models can continue deriving `NativeConverter`; input and output need not choose the same optional integration.

## Own the conversion explicitly

The separate `nativeConverterZIOSchemaExt` project provides a direct native-value codec without depending on h3:

```scala
import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import zio.schema.Schema

val codec = NativeSchema(Schema[CreateNote])
val decoded = codec.decode(nativeValue)
val encoded = codec.encode(CreateNote("A note"))
val converter = codec.converter
```

Here `nativeValue` is a `scala.scalajs.js.Any`. `decode` returns `Either[NativeSchema.InvalidValue.type, CreateNote]` for expected input rejection. Configuration errors and exceptions from schema functions remain exceptions.

`codec.converter` is an explicit `NativeConverter[CreateNote]`. Install it as a local given only where you want that wire format. The module never installs a blanket `Schema[A] => NativeConverter[A]` given, so it cannot silently replace or compete with an application's derived or custom converter.

The plain NativeConverter reader retains its existing behavior even when this extension is imported. Use `readBodyWithSchema` when you want the schema request error policy below; passing a schema-derived converter to the plain reader does not change that reader's error policy.

## Request errors

The schema reader explicitly requests strict JSON parsing, including `application/json; charset=utf-8` requests. Malformed JSON and expected structural/domain rejection produce a safe HTTP 422 response:

```json
{ "errors": ["Request body does not match schema"] }
```

The bridge does not expose raw validation messages, submitted values, or transform rejection strings. An exception in a validator, transform, or decoder remains an unexpected error: opaque HTTP 500 outside development, with the existing development diagnostics when enabled. Unsupported schema shapes and invalid configured defaults are configuration errors, not client validation failures.

An application can customize only expected failures while preserving unexpected ones:

```scala
ZHandler.readBodyWithSchema[CreateNote].catchSome {
  case _: InvalidBody => ZHandler.failWith(400, "Please provide a valid note")
}
```

Import `InvalidBody` from `dev.yuri4n.exts.zioh3schema`. Avoid a catch-all mapping from `Throwable` to a safe error; it would misclassify programming and runtime failures.

## Supported native schema forms

This first bridge supports a bounded JSON-like representation:

| Schema form | Native representation and behavior |
| --- | --- |
| `String`, `Boolean` | Strict native primitive types; required null values are rejected |
| `Int` | Integral JavaScript number within the signed 32-bit range |
| `Double` | Finite JavaScript number |
| Records/case classes | Plain native objects; fields constructed in schema order |
| `Option` | Null or missing maps to `None`; present values use the element schema |
| Sequences | Native arrays with each element decoded by its schema |
| Lazy schemas | Resolve through their underlying schema, including recursive models |
| Transforms | Decode through the input schema and execute the forward transform once |
| Validation | Field validators and schema `.validation` annotations |

The bridge respects field names, aliases, explicit defaults, transient fields, and `rejectExtraFields`. Unknown fields are otherwise ignored. Providing multiple aliases for the same field is rejected. Overlapping names or aliases across different fields are rejected as schema configuration errors. Defaults are already typed values; their validation does not rerun forward decoding transforms.

Lazy schemas forward annotations to their underlying schema. Transforms also copy input annotations. The bridge accounts for both behaviors so inherited validators run once at the correct type.

Native/schema traversal is limited to 128 levels. Enums, maps, sets, tuples, either/fallback schemas, dynamic schemas, and other primitive representations are not supported by this slice. Unsupported shapes fail explicitly when the codec is created, before a request body is decoded. Do not assume parity with every ZIO Schema JSON codec.

## Dependencies and compatibility

| Integration | Required schema dependencies | Evidence boundary |
| --- | --- | --- |
| NativeConverter-only core | None | Existing handler/stream suite, plus a NativeConverter-only request with the schema extension imported |
| `nativeConverterZIOSchemaExt` | ZIO Schema 1.7.5, NativeConverter 0.9.0 | Direct native decoding, encoding, validation, and converter tests |
| `zioH3SchemaExt` | The native schema bridge and existing ZIO handler API | Real h3 request parsing and safe/unsafe error-boundary tests |
| Derived application schemas | ZIO Schema Derivation 1.7.5 | Actual `CreateNote` model tests and application development linking |

The verified build uses Scala 3.5.2, Scala.js 1.20.1, h3 1.15.4, and Node 24.19.0. The resolved application Compile and Test classpaths both use ZIO and ZIO Streams 2.1.23; ZIO Test is aligned to 2.1.23. This is local development evidence, not CI/release validation or a claim about untested combinations. The application retains its existing schema dependencies; the reusable ZIO handler Compile classpath contains no ZIO Schema jars. The core projects' existing ZIO Prelude dependency is now explicit at 1.0.0-RC41 rather than inherited through Schema.

This local profile runs on Debian 13.6, Linux x86_64, with Java 21 and sbt 1.11.7. It has no Nix lock/toolchain profile; Nix/NixOS and CI validation remain pending. A future compatibility matrix should tie each combination to its build profile and the same sbt/pnpm pipeline.

## Future format and effect bridges

The model schema is the source of structural and domain rules for applications that opt in. Format adapters should reuse it while making wire-format differences explicit. NativeConverter-only applications remain supported independently.

This slice implements ZIO Schema ↔ native values and an explicit NativeConverter adapter, plus the ZIO request integration. It does not stringify or reparse JSON to transfer native values. h3 still parses actual HTTP JSON text, which is a transport operation.

Schema-backed JSON and YAML text/AST adapters, Kyo Schema integration, and generated TypeScript wire types are separate extension points. They are not implemented or validated here. Kyo should use its own schema/effect integration rather than acquiring a mandatory ZIO runtime. No universal schema translation or converter compatibility is implied.

## Checks

From the repository root:

```sh
sbt 'nativeConverterZIOSchemaExt/test' 'zioH3SchemaExt/test' 'zioH3Ext/test' 'nuxtsvr/test' 'nuxtsvr/fastLinkJS'
```

Local reports confirm 15 native-schema tests, 7 h3 schema-request tests, 31 existing handler/stream tests, and 2 tests of the actual `CreateNote` model, all passing. Application `fastLinkJS` also passes. The tests exercise required/missing/null/wrong-type values, malformed JSON, domain constraints, normalization, field and schema validation, transform execution, nested collections, defaults, wire-name collisions, native property order, explicit converter ownership, and the safe/unsafe error boundary. The existing NativeConverter-only tests remain in place.

The HTTP smoke script now submits padded text and expects the schema-normalized value. It also expects the generic safe schema response. Run it against a configured development application as described in [development.md](development.md); it writes one note.
