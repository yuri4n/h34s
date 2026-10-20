package dev.yuri4n.exts.h3

import org.getshaka.nativeconverter.NativeConverter

import scala.collection.{immutable, mutable}

/** Finds the TypeScript type of what a `NativeConverter[A]` writes.
  *
  * The handler definitions ask for it next to the converter, so you never write or import it. The
  * type comes from the converter instance that encodes the body, never from the Scala type alone:
  *
  *   - a converter that mixes in `WireType.Described` (for example a `NativeSchema`) gives its own
  *     type;
  *   - native-converter's built-in converters give theirs: `String`, `Char` and `Long` are strings
  *     (native-converter writes `Long` as text), `Int`, `Short`, `Byte`, `Float` and `Double` are
  *     numbers, `Boolean` is a boolean;
  *   - native-converter's collections (`List`, `Vector`, `Seq`, `Set`, `Iterable`, `Array`, `Buffer`)
  *     are arrays, `Option` is `T | null` and `Map[String, A]` is `Record<string, T>`, when the
  *     converter in use is native-converter's own; a custom collection converter gives `unknown`;
  *   - any other converter (a derived `NativeConverter`, a hand-written one) gives `unknown`.
  */
trait WireShape[A]:
    def of(converter: NativeConverter[A]): WireType

object WireShape extends LowPriorityWireShape:
    given list: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[List[A]] =
      sequence(_.isInstanceOf[NativeConverter.ListConv[?]], element, shape)

    given vector: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[Vector[A]] =
      sequence(_.isInstanceOf[NativeConverter.VectorConv[?]], element, shape)

    given seq: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[collection.Seq[A]] =
      sequence(_.isInstanceOf[NativeConverter.SeqConv[?]], element, shape)

    given immutableSeq: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[immutable.Seq[A]] =
      sequence(_.isInstanceOf[NativeConverter.ImmutableSeqConv[?]], element, shape)

    given iterable: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[Iterable[A]] =
      sequence(_.isInstanceOf[NativeConverter.IterableConv[?]], element, shape)

    given set: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[collection.Set[A]] =
      sequence(_.isInstanceOf[NativeConverter.SetCodec[?]], element, shape)

    given immutableSet: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[immutable.Set[A]] =
      sequence(_.isInstanceOf[NativeConverter.ImmutableSetCodec[?]], element, shape)

    given buffer: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[mutable.Buffer[A]] =
      sequence(_.isInstanceOf[NativeConverter.BufferConv[?]], element, shape)

    given array: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[Array[A]] =
      sequence(_.isInstanceOf[NativeConverter.ArrayConv[?]], element, shape)

    given option: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[Option[A]] =
      checked(_.isInstanceOf[NativeConverter.OptionCodec[?]], WireType.nullable(shape.of(element)))

    given map: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[collection.Map[String, A]] =
      checked(_.isInstanceOf[NativeConverter.MapConv[?]], WireType.RecordOf(shape.of(element)))

    given immutableMap: [A] => (element: NativeConverter[A], shape: WireShape[A]) => WireShape[immutable.Map[String, A]] =
      checked(_.isInstanceOf[NativeConverter.ImmutableMapConv[?]], WireType.RecordOf(shape.of(element)))

    /** The type of a converter that is not one of native-converter's collections. */
    def leaf(converter: NativeConverter[?]): WireType = converter match
        case described: WireType.Described => described.wireType
        case _ =>
          val instance = converter.asInstanceOf[AnyRef]
          if instance eq NativeConverter.StringConv then WireType.StringValue
          else if instance eq NativeConverter.BooleanConv then WireType.BooleanValue
          else if numbers.exists(_ eq instance) then WireType.NumberValue
          else if strings.exists(_ eq instance) then WireType.StringValue
          else if instance eq NativeConverter.NullConv then WireType.NullValue
          else WireType.Unknown

    private val numbers: List[AnyRef] = List(
      NativeConverter.IntConv,
      NativeConverter.ShortConv,
      NativeConverter.ByteConv,
      NativeConverter.FloatConv,
      NativeConverter.DoubleConv,
    )

    // Not UUIDConv: referring to it links `UUID.nameUUIDFromBytes`, which Scala.js does not have.
    private val strings: List[AnyRef] = List(NativeConverter.CharConv, NativeConverter.LongConv)

    /** An array of the element's type, if `builtIn` holds for the converter in use. */
    private def sequence[C, A](builtIn: NativeConverter[C] => Boolean, element: NativeConverter[A], shape: WireShape[A]) =
      checked(builtIn, WireType.ArrayOf(shape.of(element)))

    /** `known` when the converter in use is the built-in one; otherwise what `leaf` finds. The type is
      * built only on request.
      */
    private def checked[C](builtIn: NativeConverter[C] => Boolean, known: => WireType): WireShape[C] =
      converter => if builtIn(converter) then known else leaf(converter)
end WireShape

trait LowPriorityWireShape:
    /** Every other type: the converter's own type, or `unknown`. */
    given any: [A] => WireShape[A] = converter => WireShape.leaf(converter)
