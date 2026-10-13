package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter

import scala.annotation.implicitNotFound
import scala.scalajs.js
import scala.util.NotGiven

/** Converts the value a handler finishes with into the value that `mkHandler` returns to h3.
  *
  * Created by Claude Code on 2026-10-13. Changed by Claude Code on 2026-10-13: `Unit` is no longer
  * a valid result; [[Responded]] takes its place.
  *
  * h3 reads the value a handler returns. `undefined` means "the response was already written".
  * `null` means "no content", status 204. Every other value becomes the response body. A handler
  * therefore finishes in one of two ways, and the compiler checks which one it is:
  *   - with a [[Responded]] value, which only the send operations on `SH3Event` and `ZHandler`
  *     return: `sendStream`, `respondWith`, `respondWithZIO`, and `sendNoContent`. It converts to
  *     `undefined`.
  *   - with a body value `A` that has a `NativeConverter[A]` in scope. `None` converts to `null`
  *     and `Some(a)` to `a`, whether the static type is `Option[A]`, `None.type`, or `Some[A]`.
  *
  * `Unit` is rejected on purpose, even when a `NativeConverter[Unit]` is in scope, for example
  * from `dev.yuri4n.exts.ntcvzio.given`. A handler that ends with `()` either sent nothing, which
  * a bare h3 app answers with 404 and Nitro's preemptive router with an empty 204, or sent a
  * response and then discarded the proof. Both are compile errors with the message below.
  *
  * The companion object and its parent trait provide the instances, and the compiler finds them
  * without an import. The `Responded` instance is monomorphic and lives in the companion object,
  * while the other instances live in the parent trait; under both the old and the new given
  * prioritization rules it wins, so a `NativeConverter[Responded]` at the call site does not
  * create an ambiguity and does not change the result. A handler that always fails has the success
  * type `Nothing`; the compiler then picks the `Responded` instance, so such a handler needs no
  * type annotation. The companion object must keep exactly one monomorphic instance: a second one
  * makes that inference ambiguous.
  *
  * The `Unit` exclusion is checked where `ResultConverter[A]` is summoned. A helper that wraps
  * `defineEventHandler` with the bound `[A: NativeConverter]` summons it for an abstract `A`, which
  * the exclusion cannot see; such a helper should use the bound `[A: ResultConverter]` instead.
  */
@implicitNotFound(
  "The handler finishes with ${A}, and zio-h3 cannot send that as a response. A handler ends in " +
    "one of two ways: with the call that sends the response (ZHandler.sendStream, " +
    "ZHandler.respondWith, and ZHandler.sendNoContent return Responded; do not `yield ()` after " +
    "them), or with a body value that has a NativeConverter in scope. Unit is never a body, and a " +
    "NativeConverter[Unit] in scope does not change that. For a body of another type, provide its " +
    "NativeConverter, for example with `derives NativeConverter` on a case class",
)
trait ResultConverter[A]:
    def toNative(a: A): js.Any

object ResultConverter extends ResultConverterLowPriority:
    /** The response was already sent. h3 reads `undefined` as "write nothing more". Changed by
      * Claude Code on 2026-10-13: replaces the `Unit` instance.
      */
    given responded: ResultConverter[Responded] = _ => js.undefined

/** Holds the other instances in a parent trait so that `ResultConverter.responded` has priority. */
trait ResultConverterLowPriority:
    /** A bare `None` is `null`, which h3 answers with 204. Without this instance the library would
      * derive a converter for the `None` singleton and send an empty object. Created by Claude Code
      * on 2026-10-13.
      */
    given none: ResultConverter[None.type] = _ => null

    /** A bare `Some(a)` is the body `a`, like `Some(a): Option[A]`. Without this instance the
      * library would derive a converter for the `Some` case class and send `{"value": a}`. Created
      * by Claude Code on 2026-10-13.
      */
    given some[A](using NotGiven[A =:= Unit])(using nc: NativeConverter[A]): ResultConverter[Some[A]] =
      s => nc.toNative(s.value)

    /** Every `A` except `Unit` that has a `NativeConverter[A]` in scope. `Unit` is excluded so that
      * a `NativeConverter[Unit]` at the call site cannot turn `()` into a response. Changed by Claude
      * Code on 2026-10-13: excludes `Unit`.
      */
    given fromNativeConverter[A](using NotGiven[A =:= Unit])(using
        nc: NativeConverter[A],
    ): ResultConverter[A] =
      a => nc.toNative(a)
