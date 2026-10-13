package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter

import scala.annotation.implicitNotFound
import scala.scalajs.js
import scala.util.NotGiven

/** Converts the value a handler finishes with into the value that `mkHandler` returns to h3.
  *
  * Created by Claude Code on 2026-10-13. Changed by Claude Code on 2026-10-13: `Unit` is no longer
  * a valid result; [[Responded]] takes its place. Changed again by Claude Code on 2026-10-13:
  * `send` joined the operations that return the proof, and the message names it for a handler
  * whose branches have different result types.
  *
  * h3 reads the value a handler returns. `undefined` means "the response was already written".
  * `null` means "no content", status 204. Every other value becomes the response body. A handler
  * therefore finishes in one of two ways, and the compiler checks which one it is:
  *   - with a [[Responded]] value, which only `send`, `sendStream`, `respondWith`, and
  *     `sendNoContent` return. It converts to `undefined`.
  *   - with a body value `A` that has a `NativeConverter[A]` in scope. `None` converts to `null`.
  *
  * `Unit` is rejected on purpose, even when a `NativeConverter[Unit]` is in scope, for example
  * from `dev.yuri4n.exts.ntcvzio.given`. A handler that ends with `()` either sent nothing, which
  * a bare h3 app answers with 404 and Nitro's preemptive router with an empty 204, or sent a
  * response and then discarded the proof. Both are compile errors with the message below.
  *
  * The companion object provides the instances, and the compiler finds them without an import.
  * The `Responded` instance is more specific than the generic one, so a `NativeConverter[Responded]`
  * at the call site does not create an ambiguity and does not change the result. A handler that
  * always fails has the success type `Nothing`; the compiler then picks the `Responded` instance,
  * so such a handler needs no type annotation.
  */
@implicitNotFound(
  "The handler finishes with ${A}, and zio-h3 cannot send that as a response. " +
    "If the handler already wrote the response, end it with the call that did so: " +
    "ZHandler.send, ZHandler.sendStream, ZHandler.respondWith, and ZHandler.sendNoContent " +
    "return Responded, so do not `yield ()` after them. To send ${A} as the body, provide a " +
    "NativeConverter[${A}], for example with `derives NativeConverter` on a case class. " +
    "If one branch sends and another returns a body, send the body with ZHandler.send so that " +
    "every branch returns Responded",
)
trait ResultConverter[A]:
    def toNative(a: A): js.Any

object ResultConverter extends ResultConverterLowPriority:
    /** The response was already sent. h3 reads `undefined` as "write nothing more". */
    given responded: ResultConverter[Responded] = _ => js.undefined

/** Holds the generic instance in a parent trait so that `ResultConverter.responded` has priority. */
trait ResultConverterLowPriority:
    /** Every `A` except `Unit` that has a `NativeConverter[A]` in scope. `Unit` is excluded so that
      * a `NativeConverter[Unit]` at the call site cannot turn `()` into a response.
      */
    given fromNativeConverter[A](using NotGiven[A =:= Unit])(using
        nc: NativeConverter[A],
    ): ResultConverter[A] =
      a => nc.toNative(a)
