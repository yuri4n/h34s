package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter

import scala.annotation.implicitNotFound
import scala.scalajs.js

/** Converts the value a handler finishes with into the value that `mkHandler` returns to h3.
  *
  * Created by Claude Code on 2026-10-13.
  *
  * h3 reads the value a handler returns. `undefined` means "no body": the handler already wrote
  * the response, for example with `sendStream`, or has nothing to send. Every other value becomes
  * the response body. The toolbox owns this rule, so a handler that finishes with `Unit` needs no
  * converter in scope, and a `NativeConverter[Unit]` at the call site does not change the result.
  *
  * The companion object provides two instances, and the compiler finds them without an import:
  *   - `Unit` converts to `js.undefined`. This instance is more specific than the generic one, so
  *     it wins when both apply. Installing `dev.yuri4n.exts.ntcvzio.given` next to it does not
  *     create an ambiguity.
  *   - Every other `A` with a `NativeConverter[A]` in scope converts through that converter.
  */
@implicitNotFound(
  "The handler finishes with ${A}, and zio-h3 cannot turn that into a JavaScript value. " +
    "Return Unit when the handler already wrote the response, or provide a NativeConverter[${A}], " +
    "for example with `derives NativeConverter` on a case class.",
)
trait ResultConverter[A]:
    def toNative(a: A): js.Any

object ResultConverter extends ResultConverterLowPriority:
    /** `()` becomes `undefined`, whatever `NativeConverter[Unit]` the call site has in scope. */
    given unit: ResultConverter[Unit] = _ => js.undefined

/** Holds the generic instance in a parent trait so that `ResultConverter.unit` has priority. */
trait ResultConverterLowPriority:
    given fromNativeConverter[A](using nc: NativeConverter[A]): ResultConverter[A] =
      a => nc.toNative(a)
