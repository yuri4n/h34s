package dev.yuri4n.exts.ntcvziojson

import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.json.ast.Json

import scala.scalajs.js

given NativeConverter[Json] with
    extension (j: Json)
      def toNative: js.Any                       =
        j match
            case Json.Null        => null
            case Json.Bool(value) => js.Any.fromBoolean(value)
            case Json.Num(value)  => js.Any.fromDouble(value.doubleValue())
            case Json.Str(value)  => js.Any.fromString(value)
            case Json.Arr(values) => js.Array(values.map(_.toNative)*)
            case Json.Obj(fields) =>
              val obj = js.Dictionary[js.Any]()
              fields.foreach { case (k, v) => obj(k) = v.toNative }
              obj
    def fromNative(parseState: ParseState): Json =
        val n = parseState.json.asInstanceOf[Matchable]
        if n == null then Json.Null
        else
            n match
                case b: Boolean       => Json.Bool(b)
                case d: Double        => Json.Num(BigDecimal(d))
                case s: String        => Json.Str(s)
                case arr: js.Array[?] =>
                  Json.Arr(arr.asInstanceOf[js.Array[js.Any]].map(fromNative).toSeq*)
                case obj: js.Object   =>
                  val dict = obj.asInstanceOf[js.Dictionary[js.Any]]
                  Json.Obj(
                    dict.map { case (k, v) => k -> fromNative(v) }.toMap.toSeq*,
                  )
        end if
    end fromNative
end given
