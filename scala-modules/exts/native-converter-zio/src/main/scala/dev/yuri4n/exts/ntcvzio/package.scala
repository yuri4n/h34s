package dev.yuri4n.exts

import org.getshaka.nativeconverter.NativeConverter
import scalajs.js
import zio.prelude.NonEmptyList
import org.getshaka.nativeconverter.ParseState

object ntcvzio:
    /** The shared `undefined <-> ()` converter, defined once in h3-ext. */
    export dev.yuri4n.exts.h3.unitNativeConverter

    extension [A](nativeConverter: NativeConverter[A])
      def eimap[B](f: A => Either[String, B], g: B => A): NativeConverter[B] =
        new NativeConverter[B]:
            extension (value: B) def toNative: js.Any = nativeConverter.toNative(g(value))

            override def fromNative(parseState: ParseState): B =
              f(nativeConverter.fromNative(parseState.json)) match
                  case Right(b)  => b
                  case Left(err) => parseState.fail(s"Error parsing value: $err")

    given [A: NativeConverter] => NativeConverter[NonEmptyList[A]] = NativeConverter.ListConv.eimap(
      {
        case head :: tail => Right(NonEmptyList(head, tail*))
        case Nil          => Left("Cannot convert empty list to NonEmptyList")
      },
      _.toList,
    )
end ntcvzio
