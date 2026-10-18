package dev.yuri4n.exts.h3

import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.scalajs.js

/** `()` converts to native `undefined`, and only `undefined` converts back to `()`. */
given unitNativeConverter: NativeConverter[Unit]:
    extension (value: Unit) def toNative: js.Any = js.undefined

    def fromNative(state: ParseState): Unit =
      if js.isUndefined(state.json) then () else state.fail("undefined")
