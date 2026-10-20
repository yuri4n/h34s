# ADR-0014: Named model types: `WireType.Named`, one models file per output, and `#h34s/models`

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the orchestrating session, which took the recommended option of each question in issue #66 under the maintainer's standing instruction to take the recommended decisions and record the other options with the reasons (see the [index](README.md)). The implementing pull request decided the details listed under "Rules found while implementing".
- **Source:** [issue #66](https://github.com/yuri4n/h34s/issues/66), "Named model types", a sub-issue of [#17](https://github.com/yuri4n/h34s/issues/17); the maintainer's request of 2026-10-20 quoted there; decision 20 of the [design record](../kyo-core.md) ([#27](https://github.com/yuri4n/h34s/issues/27)).

## Context

<!-- claude 2026-10-20: created: context -->

The maintainer asked on 2026-10-20: "What if a user wants to use the model they are returning from the API in the UI? We need the user to be able to generate this at ease. I did this already: I wrote a couple of helpers to generate some typeclasses scala-ts needed, and when a type was out I was able to use it (`useAsyncData<Note>`)." Those helpers were on a machine the maintainer no longer has. A search of every ref, tag, reflog entry, pull ref and unreachable object found nothing: they predate this repository's history, and `docs/baseline.md` at `d332fd2` already says that the scala-ts plugin was listed but not wired up.

PR [#15](https://github.com/yuri4n/h34s/pull/15) generates one `<module>.d.ts` per handler, from what each `NativeConverter` writes (`WireType`). `$fetch('/api/notes')` is typed, but no type has a name:

- `WireType` had no case that carries a name.
- Both schema bridges dropped the name they already have. Kyo's `Structure.Type.Product` and `Sum` carry `name` and `typeParams`; ZIO's `Schema.Record.id` is a `TypeId.Nominal` with `name` and `fullyQualified`.
- Every route inlined its own copy of each model, and the example hand-wrote `Promise<{ id: number, text: string }[]>` because it could not say `NoteView[]`.

Facts that the implementing pull request checked (tests in `NativeSchemaSuite` and `NativeSchemaSpec`):

- Kyo's `name` is the simple name (`Person`). The type's tag gives the full name: `tag.show` is `dev.yuri4n.exts.ntcvkyoschema.Models$.Person`. For the instantiation `Page[Person]`, `typeParams` holds the `Person` structure, and `tag.show` is `scala.Any`.
- A recursive Kyo model (`Tree(value: Int, children: List[Tree])`) has a structure that never ends. The bridge cuts it at depth 16, and its sample check fails, so its type is `unknown`.
- ZIO's `TypeId.Nominal` has no type arguments: `DeriveSchema.gen[Page[Pair]]` gives the name `Page`. ZIO's `Schema.Enum` stays unsupported by the bridge (`UnsupportedSchema`).
- Nuxt 4.3.1 turns every `nuxt.options.alias` entry into a `paths` entry of `.nuxt/tsconfig*.json`, and its generated tsconfig sets `skipLibCheck: true`.

## Decision

<!-- claude 2026-10-20: created: decision -->

The six decisions of issue #66:

1. **Names ride the converter description.** `WireType` gets the case `Named(name, target)`. Both schema bridges wrap every record and sum with its nominal name. A hand-written `WireType.Described` converter can use `Named` directly.
2. **One models file per output directory.** `h34s-scala` writes `h34s-models.d.ts` next to the route declarations, with one `export type Note = { ... }` for every named model. Each route `.d.ts` imports the names it uses instead of inlining them. The route manifest lists the file.
3. **The UI reaches the models through a Nuxt alias.** The h34s Nuxt module registers `#h34s/models`, so the UI writes `import type { Note } from '#h34s/models'`.
4. **Name clashes fail loudly.** The same name with the same structure is one declaration. The same name with different structures fails the generation, and the message names both Scala types and the routes that use them. h34s never adds a suffix.
5. **Generic instantiations are inlined.** `Page[Note]` is written out as its structure, and `Note` keeps its name inside.
6. **Response models only.** Models that no route returns, such as request bodies (`CreateNote`), get their names with the typed route registry ([#46](https://github.com/yuri4n/h34s/issues/46)), where the route declares its body type.

### Rules found while implementing

<!-- claude 2026-10-20: created: the rules that the implementing pull request decided -->

- **The case has a third field.** `Named(name, target, scalaType: Option[String] = None)`. The clash message needs the Scala type's full name, and only the bridges know it. The default keeps `Named(name, target)` working for hand-written converters.
- **`render` keeps writing every model out.** The record's `success` string stays as PR #15 wrote it, so callers that want one expression keep working, and an older `h34s-scala` still writes correct declarations. The internal `renderNamed` writes references, the internal `models` lists the declarations in dependency order, and the record adds `named: { success, uses, models }`. `RouteTypesSuite` keeps the rendering of commit `5f894e9` as an oracle and checks the new rendering against it in two properties of 200 random types each.
- **Only `WireType.Named` is new public Scala API.** Decision 1 names only the case. The helpers that `HandlerMetadata` and the tests use (`renderNamed`, `models`, `WireModel` and `reservedNames`) are `private[exts]`, so that they can change without a migration entry.
- **Some names are never exported.** A `Named` whose target is `Unknown` has nothing to declare: it renders `unknown` and is not exported. A name that is not a TypeScript identifier, or that TypeScript or the generated declarations use (keywords, `Array`, `Record`, `Promise`, `SuccessBody`, `ErrorBody`, `EventHandler`, `EventHandlerRequest`), is written out where it is used. A Scala.js module ID `h34s-models` fails the generation, because its declaration would replace the models file.
- **A model refers to itself.** Inside a model's structure, a `Named` with the same name (and the same Scala type when both are known) is a reference to that model. The ZIO bridge marks the place where a recursive record repeats this way, so a recursive record gives `type Tree = { value: number; children: Array<Tree> }`. A recursive Kyo model stays `unknown`, because its structure never ends.
- **The variants of a sum stay inside the sum.** Enum cases and the case classes of a sealed trait keep the rendering they had inside the named sum (`{ Circle: { radius: number } } | ...`), so a discriminated union still merges its tag into each variant. With external tagging the payload is the variant's own structure, but the variants stay unnamed there too: one rule for every tagging keeps a sum's models independent of its tagging annotation, and it keeps the case names of different sums (two `Circle`s, two `Other`s) from stopping the generation with a clash. Naming the variants of an externally tagged sum is a possible follow-up.
- **Kyo generics are found by their type parameters; ZIO generics by their structures.** The Kyo bridge does not name a type with non-empty `typeParams`. The ZIO bridge cannot tell an instantiation from a plain record, so it names `Page[Pair]` as `Page`. When one Scala type has two structures, in one output or across outputs (`#h34s/models` puts every output into one namespace), `h34s-scala` treats it as a generic: it writes that model out in every output, with every model that uses it, and every route that refers to one of them gets its fully written-out type. A ZIO generic that only one instantiation uses keeps the name of its class.
- **Several outputs share one alias.** With one output, `#h34s/models` is that output's `h34s-models.d.ts`. With several, it is `.nuxt/h34s/models.d.ts`, a Nuxt template with one `export type * from` line per output. Because the alias puts every output into one namespace, `h34s-scala` merges the models of every output before it writes any file, and it also fails when two Scala types give one name different structures in two outputs. An alias that the application sets wins.
- **Every output gets a models file.** An output without models gets an empty module (`export {}`), so that `#h34s/models` resolves after the first link.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

From issue #66:

- **1. Names ride the converter description (`WireType.Named`).** Chosen: the named types come from the same description as the wire types, so a custom converter's format stays true.
- **1'. Reinstate scala-ts.** Not chosen: scala-ts describes Scala types, not what a converter writes, which is the reason for decision 20 of the design record ([#27](https://github.com/yuri4n/h34s/issues/27)). A custom converter's wire format would be guessed.
- **2. One models file per output directory.** Chosen.
- **2'. One file per model.** Not chosen: more files, no gain.
- **3. A Nuxt alias, `#h34s/models`.** Chosen.
- **3'. Auto-imported types (unimport `type: true`), so the UI writes `useAsyncData<Note>` with no import line.** Deferred: Nuxt's type templates would have to be regenerated whenever the models change in development. A follow-up issue tracks it, under the maintainer's cost rule.
- **4. Fail loudly on a clash.** Chosen.
- **4'. Add a suffix silently.** Not chosen: the issue rules out silent suffixing; a `Note2` would change the app's types without a warning.
- **5. Inline generic instantiations.** Chosen for this round.
- **5'. TypeScript generics (`Page<A>`).** A follow-up.
- **6. Response models only.** Chosen for this round. The issue leaves one question for the maintainer: is a request-body type needed in the first cut?

From the implementing pull request (the issue does not list these):

- **Several outputs: one aggregate template.** Chosen, as described above.
- **Several outputs: one alias per output.** Not chosen: the UI would have to know which Scala.js output returns a model.
- **Several outputs: the alias points at the first output only.** Not chosen: the example's Kyo `Greeting` would be out of reach.
- **The aggregate lists each name explicitly instead of `export type *`.** Not chosen: the template would depend on the manifests and need regenerating in development whenever a model is added. The cost is one case: two outputs that export the same name with the same structure give TypeScript error TS2308 inside the generated file when an application turns `skipLibCheck` off (Nuxt sets it on).
- **ZIO generics: fail the generation.** Not chosen: a ZIO record used with two type arguments, which works today, would stop working.
- **ZIO generics: detect type parameters with a macro in `NativeSchema.derived`.** Not chosen: the macro only sees the top-level type, while nested records are reached through their `Schema`.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- **No breaking change in the generated files' meaning.** Every route body has the same structure as before. A route declaration now refers to models by name, and a new file sits next to it. `WireType` gains a case, so an exhaustive `match` over `WireType` in application code warns until it handles `Named`. No public name changes, so the migration guide does not change.
- **The record gains `named`, and the manifest gains `models`.** `h34s-scala` from this release reads both. An older `h34s-scala` ignores `named` and writes the inline types.
- **The example drops its hand-written shape.** `examples/nuxt` imports `NoteView` and `Greeting` from `#h34s/models`, and its `/notes` page lists notes with `useAsyncData<NoteView[]>` and a typed `NoteList` prop.
- **Known gaps.** A ZIO generic used with one type argument is named after its class. A recursive Kyo model is `unknown`. Request bodies have no named type.
- **CI does not type-check the example.** `scripts/check-route-types.mjs`, which checks `#h34s/models` in the example, is a manual check in [development.md](../../development.md).

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Open the follow-up issue for auto-imported model types (decision 3'), and link it here.
- TypeScript generics for generic models (decision 5').
- Request-body models with the typed route registry ([#46](https://github.com/yuri4n/h34s/issues/46), decision 6).
- Recursive Kyo models: refer to the model by name where the structure repeats, and stop the bridge's sample check there.
- A ZIO generic used with one type argument: find a way to tell it from a plain record.
- Run `scripts/check-route-types.mjs` in CI.
- Comment on issue #66 with the pull request and close it.
