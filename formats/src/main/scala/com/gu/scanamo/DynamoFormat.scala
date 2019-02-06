package org.scanamo

import java.util
import java.util.UUID

import cats.NotNull
import cats.instances.either._
import cats.instances.list._
import cats.instances.sortedMap._
import cats.instances.string._
import cats.instances.vector._
import cats.syntax.either._
import cats.syntax.traverse._
import org.scanamo.error._
import simulacrum.typeclass
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.collection.JavaConverters._
import scala.collection.immutable.SortedMap
import scala.reflect.ClassTag

/**
  * Type class for defining serialisation to and from
  * DynamoDB's `AttributeValue`
  *
  * {{{
  * >>> val listOptionFormat = DynamoFormat[List[Option[Int]]]
  * >>> listOptionFormat.read(listOptionFormat.write(List(Some(1), None, Some(3))))
  * Right(List(Some(1), None, Some(3)))
  *
  * Also supports automatic and semi-automatic derivation for case classes
  *
  * }}}
  *
  * {{{
  * >>> import org.scanamo.auto._
  * >>>
  * >>> case class Farm(animals: List[String])
  * >>> case class Farmer(name: String, age: Long, farm: Farm)
  * >>> val farmerF = DynamoFormat[Farmer]
  * >>> farmerF.read(farmerF.write(Farmer("McDonald", 156L, Farm(List("sheep", "cow")))))
  * Right(Farmer(McDonald,156,Farm(List(sheep, cow))))
  * }}}
  *
  * and for sealed trait + case object hierarchies
  *
  * {{{
  * >>> sealed trait Animal
  * >>> case object Aardvark extends Animal
  * >>> case object Zebra extends Animal
  * >>> case class Pet(name: String, animal: Animal)
  * >>> val petF = DynamoFormat[Pet]
  * >>> petF.read(petF.write(Pet("Amy", Aardvark)))
  * Right(Pet(Amy,Aardvark))
  *
  * >>> petF.read(petF.write(Pet("Zebediah", Zebra)))
  * Right(Pet(Zebediah,Zebra))
  * }}}
  *
  * Problems reading a value are detailed
  * {{{
  * >>> import cats.syntax.either._
  *
  * >>> case class Developer(name: String, age: String, problems: Int)
  * >>> val invalid = DynamoFormat[Farmer].read(DynamoFormat[Developer].write(Developer("Alice", "none of your business", 99)))
  * >>> invalid
  * Left(InvalidPropertiesError(NonEmptyList(PropertyReadError(age,NoPropertyOfType(N,{S: none of your business})), PropertyReadError(farm,MissingProperty))))
  *
  * >>> invalid.leftMap(cats.Show[error.DynamoReadError].show)
  * Left('age': not of type: 'N' was '{S: none of your business}', 'farm': missing)
  * }}}
  *
  * Optional properties are defaulted to None
  * {{{
  * >>> case class LargelyOptional(a: Option[String], b: Option[String])
  * >>> DynamoFormat[LargelyOptional].read(DynamoFormat[Map[String, String]].write(Map("b" -> "X")))
  * Right(LargelyOptional(None,Some(X)))
  * }}}
  *
  * Custom formats can often be most easily defined using [[DynamoFormat.coercedXmap]], [[DynamoFormat.xmap]] or [[DynamoFormat.iso]]
  */
@typeclass trait DynamoFormat[T] {
  def read(av: AttributeValue): Either[DynamoReadError, T]
  def write(t: T): AttributeValue
  def default: Option[T] = None
}

object DynamoFormat extends EnumDynamoFormat {

  private def attribute[T](decode: AttributeValue => T, propertyType: String)(
    encode: AttributeValue.Builder => T => AttributeValue.Builder
  ): DynamoFormat[T] =
    new DynamoFormat[T] {
      override def read(av: AttributeValue): Either[DynamoReadError, T] = {
        Either.fromOption(Option(decode(av)), NoPropertyOfType(propertyType, av))
      }
      override def write(t: T): AttributeValue = {
        encode(AttributeValue.builder()).apply(t).build()
      }
    }

  /**
    * Returns a [[DynamoFormat]] for the case where `A` and `B` are isomorphic,
    * i.e. an `A` can always be converted to a `B` and vice versa.
    *
    * If there are some values of `B` that have no corresponding value in `A`,
    * use [[DynamoFormat.xmap]] or [[DynamoFormat.coercedXmap]].
    *
    * {{{
    * >>> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    *
    * >>> case class UserId(value: String)
    *
    * >>> implicit val userIdFormat =
    * ...   DynamoFormat.iso[UserId, String](UserId.apply)(_.value)
    * >>> DynamoFormat[UserId].read(AttributeValue.builder().s("Eric").build())
    * Right(UserId(Eric))
    * }}}
    */
  def iso[A, B](r: B => A)(w: A => B)(implicit f: DynamoFormat[B]) = new DynamoFormat[A] {
    override def read(item: AttributeValue): Either[DynamoReadError, A] = f.read(item).map(r)
    override def write(t: A): AttributeValue = f.write(w(t))
    override val default: Option[A] = f.default.map(r)
  }

  /**
    * {{{
    * >>> import org.joda.time._
    * >>> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    *
    * >>> implicit val jodaLongFormat = DynamoFormat.xmap[DateTime, Long](
    * ...   l => Right(new DateTime(l).withZone(DateTimeZone.UTC))
    * ... )(
    * ...   _.withZone(DateTimeZone.UTC).getMillis
    * ... )
    * >>> DynamoFormat[DateTime].read(AttributeValue.builder().n("0").build())
    * Right(1970-01-01T00:00:00.000Z)
    * }}}
    */
  def xmap[A, B](r: B => Either[DynamoReadError, A])(w: A => B)(implicit f: DynamoFormat[B]) = new DynamoFormat[A] {
    override def read(item: AttributeValue): Either[DynamoReadError, A] = f.read(item).flatMap(r)
    override def write(t: A): AttributeValue = f.write(w(t))
  }
  /**
    * Returns a [[DynamoFormat]] for the case where `A` can always be converted `B`,
    * with `write`, but `read` may throw an exception for some value of `B`
    *
    * {{{
    * >>> import org.joda.time._
    *
    * >>> val jodaStringFormat = DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](
    * ...   LocalDate.parse
    * ... )(
    * ...   _.toString
    * ... )
    * >>> jodaStringFormat.read(jodaStringFormat.write(new LocalDate(2007, 8, 18)))
    * Right(2007-08-18)
    *
    * >>> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * >>> jodaStringFormat.read(AttributeValue.builder().s("Togtogdenoggleplop").build())
    * Left(TypeCoercionError(java.lang.IllegalArgumentException: Invalid format: "Togtogdenoggleplop"))
    * }}}
    */
  def coercedXmap[A, B, T >: scala.Null <: scala.Throwable](
    read: B => A
  )(write: A => B)(implicit f: DynamoFormat[B], T: ClassTag[T], NT: NotNull[T]) =
    xmap(coerce[B, A, T](read))(write)

  /**
    * {{{
    * prop> (s: String) =>
    *     | DynamoFormat[String].read(DynamoFormat[String].write(s)) == Right(s)
    * }}}
    */
  implicit val stringFormat: DynamoFormat[String] = attribute[String](_.s, "S")(_.s)

  private val javaBooleanFormat = attribute[java.lang.Boolean](_.bool, "BOOL")(_.bool)

  /**
    * {{{
    * prop> (b: Boolean) =>
    *     | DynamoFormat[Boolean].read(DynamoFormat[Boolean].write(b)) == Right(b)
    * }}}
    */
  implicit val booleanFormat = xmap[Boolean, java.lang.Boolean](b => Right(Boolean.unbox(b)))(
    Boolean.box
  )(javaBooleanFormat)


  /**
    * {{{
    * prop> (b: Boolean) =>
    *     | DynamoFormat[Boolean].read(DynamoFormat[Boolean].write(b)) == Right(b)
    * }}}
    */
  private val numFormat = attribute(_.n, "N")(_.n)
  private def coerceNumber[N](f: String => N): String => Either[DynamoReadError, N] =
    coerce[String, N, NumberFormatException](f)

  private def coerce[A, B, T >: scala.Null <: scala.Throwable](
    f: A => B
  )(implicit T: ClassTag[T], NT: NotNull[T]): A => Either[DynamoReadError, B] =
    a => Either.catchOnly[T](f(a)).leftMap(TypeCoercionError(_))

  /**
    * {{{
    * prop> (l: Long) =>
    *     | DynamoFormat[Long].read(DynamoFormat[Long].write(l)) == Right(l)
    * }}}
    */
  implicit val longFormat = xmap(coerceNumber(_.toLong))(_.toString)(numFormat)

  /**
    * {{{
    * prop> (i: Int) =>
    *     | DynamoFormat[Int].read(DynamoFormat[Int].write(i)) == Right(i)
    * }}}
    */
  implicit val intFormat = xmap(coerceNumber(_.toInt))(_.toString)(numFormat)

  /**
    * {{{
    * prop> (d: Float) =>
    *     | DynamoFormat[Float].read(DynamoFormat[Float].write(d)) == Right(d)
    * }}}
    */
  implicit val floatFormat = xmap(coerceNumber(_.toFloat))(_.toString)(numFormat)

  /**
    * {{{
    * prop> (d: Double) =>
    *     | DynamoFormat[Double].read(DynamoFormat[Double].write(d)) == Right(d)
    * }}}
    */
  implicit val doubleFormat = xmap(coerceNumber(_.toDouble))(_.toString)(numFormat)

  /**
    * {{{
    * prop> (d: BigDecimal) =>
    *     | DynamoFormat[BigDecimal].read(DynamoFormat[BigDecimal].write(d)) == Right(d)
    * }}}
    */
  implicit val bigDecimalFormat = xmap(coerceNumber(BigDecimal(_)))(_.toString)(numFormat)

  /**
    * {{{
    * prop> (s: Short) =>
    *     | DynamoFormat[Short].read(DynamoFormat[Short].write(s)) == Right(s)
    * }}}
    */
  implicit val shortFormat = xmap(coerceNumber(_.toShort))(_.toString)(numFormat)

  /**
    * {{{
    * prop> (b: Byte) =>
    *     | DynamoFormat[Byte].read(DynamoFormat[Byte].write(b)) == Right(b)
    * }}}
    */
  // Thrift and therefore Scanamo-Scrooge provides a byte and binary types backed by byte and byte[].
  implicit val byteFormat = xmap(coerceNumber(_.toByte))(_.toString)(numFormat)

  // Since AttributeValue includes a SdkBytes instance, creating byteArray format backed by SdkBytes
  private val javaByteBufferFormat = attribute[SdkBytes](_.b, "B")(_.b)

  private def coerceByteBuffer[B](f: SdkBytes => B): SdkBytes => Either[DynamoReadError, B] =
    coerce[SdkBytes, B, IllegalArgumentException](f)

  /**
    * {{{
    * prop> (ab:Array[Byte]) =>
    *     | DynamoFormat[Array[Byte]].read(DynamoFormat[Array[Byte]].write(ab)).map(i => new String(i)) == Right(new String(ab))
    * }}}
    */
  implicit val byteArrayFormat: DynamoFormat[Array[Byte]] = xmap(coerceByteBuffer(_.asByteArray()))(SdkBytes.fromByteArray)(javaByteBufferFormat)

  /**
    * {{{
    * prop> implicit val arbitraryUUID = org.scalacheck.Arbitrary(org.scalacheck.Gen.uuid)
    * prop> (uuid: java.util.UUID) =>
    *     | DynamoFormat[java.util.UUID].read(DynamoFormat[java.util.UUID].write(uuid)) ==
    *     |   Right(uuid)
    * }}}
    */
  implicit val uuidFormat: DynamoFormat[UUID] = coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  val javaListFormat: DynamoFormat[util.List[AttributeValue]] = attribute(_.l, "L")(_.l)

  /**
    * {{{
    * prop> (l: List[String]) =>
    *     | DynamoFormat[List[String]].read(DynamoFormat[List[String]].write(l)) ==
    *     |   Right(l)
    * }}}
    */
  implicit def listFormat[T](implicit f: DynamoFormat[T]): DynamoFormat[List[T]] =
    xmap[List[T], java.util.List[AttributeValue]](_.asScala.toList.traverse(f.read))(
      _.map(f.write).asJava
    )(javaListFormat)

  /**
    * {{{
    * prop> (sq: Seq[String]) =>
    *     | DynamoFormat[Seq[String]].read(DynamoFormat[Seq[String]].write(sq)) ==
    *     |   Right(sq)
    * }}}
    */
  implicit def seqFormat[T](implicit f: DynamoFormat[T]): DynamoFormat[Seq[T]] =
    xmap[Seq[T], List[T]](l => Right(l.toSeq))(_.toList)

  /**
    * {{{
    * prop> (v: Vector[String]) =>
    *     | DynamoFormat[Vector[String]].read(DynamoFormat[Vector[String]].write(v)) ==
    *     |   Right(v)
    * }}}
    */
  implicit def vectorFormat[T](implicit f: DynamoFormat[T]): DynamoFormat[Vector[T]] =
    xmap[Vector[T], java.util.List[AttributeValue]](_.asScala.toVector.traverse(f.read))(
      _.map(f.write).asJava
    )(javaListFormat)

  /**
    * {{{
    * prop> (a: Array[String]) =>
    *     | DynamoFormat[Array[String]].read(DynamoFormat[Array[String]].write(a)).right.getOrElse(Array("error")).deep ==
    *     |   a.deep
    * }}}
    */
  implicit def arrayFormat[T: ClassTag](implicit f: DynamoFormat[T]): DynamoFormat[Array[T]] =
    xmap[Array[T], java.util.List[AttributeValue]](_.asScala.toList.traverse(f.read).map(_.toArray))(
      _.map(f.write).toList.asJava
    )(javaListFormat)

  private def numSetFormat[T](r: String => Either[DynamoReadError, T])(w: T => String): DynamoFormat[Set[T]] =
    new DynamoFormat[Set[T]] {
      override def read(av: AttributeValue): Either[DynamoReadError, Set[T]] =
        for {
          ns <- Either.fromOption(
            if ((av.nul() ne null) && av.nul()) Some(Nil) else Option(av.ns()).map(_.asScala.toList),
            NoPropertyOfType("NS", av)
          )
          set <- ns.traverse(r)
        } yield set.toSet
      // Set types cannot be empty
      override def write(t: Set[T]): AttributeValue = t.toList match {
        case Nil => AttributeValue.builder().nul(true).build()
        case xs  => AttributeValue.builder().ns(xs.map(w).asJava).build()
      }
      override val default: Option[Set[T]] = Some(Set.empty)
    }

  /**
    * {{{
    * prop> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * prop> import org.scalacheck._
    * prop> implicit def arbNonEmptySet[T: Arbitrary] = Arbitrary(Gen.nonEmptyContainerOf[Set, T](Arbitrary.arbitrary[T]))
    *
    * prop> (s: Set[Int]) =>
    *     | val av = AttributeValue.builder.ns(s.toList.map(_.toString): _*).build()
    *     | DynamoFormat[Set[Int]].write(s) == av &&
    *     |   DynamoFormat[Set[Int]].read(av) == Right(s)
    *
    * >>> DynamoFormat[Set[Int]].write(Set.empty).nul()
    * true
    * }}}
    */
  implicit val intSetFormat: DynamoFormat[Set[Int]] = numSetFormat(coerceNumber(_.toInt))(_.toString)

  /**
    * {{{
    * prop> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * prop> import org.scalacheck._
    * prop> implicit def arbNonEmptySet[T: Arbitrary] = Arbitrary(Gen.nonEmptyContainerOf[Set, T](Arbitrary.arbitrary[T]))
    *
    * prop> (s: Set[Long]) =>
    *     | val av = AttributeValue.builder().ns(s.toList.map(_.toString): _*).build()
    *     | DynamoFormat[Set[Long]].write(s) == av &&
    *     |   DynamoFormat[Set[Long]].read(av) == Right(s)
    *
    * >>> DynamoFormat[Set[Long]].write(Set.empty).nul()
    * true
    * }}}
    */
  implicit val longSetFormat: DynamoFormat[Set[Long]] = numSetFormat(coerceNumber(_.toLong))(_.toString)

  /**
    * {{{
    * prop> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * prop> import org.scalacheck._
    * prop> implicit def arbNonEmptySet[T: Arbitrary] = Arbitrary(Gen.nonEmptyContainerOf[Set, T](Arbitrary.arbitrary[T]))
    *
    * prop> (s: Set[Float]) =>
    *     | val av = AttributeValue.builder().ns(s.toList.map(_.toString): _*).build()
    *     | DynamoFormat[Set[Float]].write(s) == av &&
    *     |   DynamoFormat[Set[Float]].read(av) == Right(s)
    *
    * >>> DynamoFormat[Set[Float]].write(Set.empty).nul()
    * true
    * }}}
    */
  implicit val floatSetFormat: DynamoFormat[Set[Float]] = numSetFormat(coerceNumber(_.toFloat))(_.toString)

  /**
    * {{{
    * prop> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * prop> import org.scalacheck._
    * prop> implicit def arbNonEmptySet[T: Arbitrary] = Arbitrary(Gen.nonEmptyContainerOf[Set, T](Arbitrary.arbitrary[T]))
    *
    * prop> (s: Set[Double]) =>
    *     | val av = AttributeValue.builder().ns(s.toList.map(_.toString): _*).build()
    *     | DynamoFormat[Set[Double]].write(s) == av &&
    *     |   DynamoFormat[Set[Double]].read(av) == Right(s)
    *
    * >>> DynamoFormat[Set[Double]].write(Set.empty).nul()
    * true
    * }}}
    */
  implicit val doubleSetFormat: DynamoFormat[Set[Double]] = numSetFormat(coerceNumber(_.toDouble))(_.toString)

  /**
    * {{{
    * prop> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * prop> import org.scalacheck._
    * prop> implicit def arbNonEmptySet[T: Arbitrary] = Arbitrary(Gen.nonEmptyContainerOf[Set, T](Arbitrary.arbitrary[T]))
    *
    * prop> (s: Set[BigDecimal]) =>
    *     | val av = AttributeValue.builder().ns(s.toList.map(_.toString): _*).build()
    *     | DynamoFormat[Set[BigDecimal]].write(s) == av &&
    *     |   DynamoFormat[Set[BigDecimal]].read(av) == Right(s)
    *
    * >>> DynamoFormat[Set[BigDecimal]].write(Set.empty).nul()
    * true
    * }}}
    */
  implicit val BigDecimalSetFormat: DynamoFormat[Set[BigDecimal]] =
    numSetFormat(coerceNumber(BigDecimal(_)))(_.toString)

  /**
    * {{{
    * prop> import software.amazon.awssdk.services.dynamodb.model.AttributeValue
    * prop> import org.scalacheck._
    * prop> implicit val arbSet = Arbitrary(Gen.nonEmptyContainerOf[Set, String](Arbitrary.arbitrary[String]))
    *
    * prop> (s: Set[String]) =>
    *     | val av = AttributeValue.builder().ss(s.toList: _*).build()
    *     | DynamoFormat[Set[String]].write(s) == av &&
    *     |   DynamoFormat[Set[String]].read(av) == Right(s)
    *
    * >>> DynamoFormat[Set[String]].write(Set.empty).nul()
    * true
    * }}}
    */
  implicit val stringSetFormat: DynamoFormat[Set[String]] =
    new DynamoFormat[Set[String]] {
      override def read(av: AttributeValue): Either[DynamoReadError, Set[String]] =
        for {
          ss <- Either.fromOption(
            if ((av.nul() ne null) && av.nul()) Some(Nil) else Option(av.ss()).map(_.asScala.toList),
            NoPropertyOfType("SS", av)
          )
        } yield ss.toSet
      // Set types cannot be empty
      override def write(t: Set[String]): AttributeValue = t.toList match {
        case Nil => AttributeValue.builder().nul(true).build()
        case xs  => AttributeValue.builder().ss(xs.asJava).build()
      }
      override val default: Option[Set[String]] = Some(Set.empty)
    }

  private val javaMapFormat = attribute(_.m, "M")(_.m)

  /**
    * {{{
    * prop> (m: Map[String, Int]) =>
    *     | DynamoFormat[Map[String, Int]].read(DynamoFormat[Map[String, Int]].write(m)) ==
    *     |   Right(m)
    * }}}
    */
  implicit def mapFormat[V](implicit f: DynamoFormat[V]): DynamoFormat[Map[String, V]] =
    xmap[Map[String, V], java.util.Map[String, AttributeValue]](
      m => (SortedMap[String, AttributeValue]() ++ m.asScala).traverse(f.read)
    )(
      _.mapValues(f.write).asJava
    )(javaMapFormat)

  /**
    * {{{
    * prop> (o: Option[Long]) =>
    *     | DynamoFormat[Option[Long]].read(DynamoFormat[Option[Long]].write(o)) ==
    *     |   Right(o)
    *
    * >>> DynamoFormat[Option[Long]].read(software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder.nul(true).build())
    * Right(None)
    *
    * >>> DynamoFormat[Option[Long]].write(None).nul()
    * true
    * }}}
    */

  implicit def optionFormat[T](implicit f: DynamoFormat[T]): DynamoFormat[Option[T]] = new DynamoFormat[Option[T]] {
    def read(av: AttributeValue): Either[DynamoReadError, Option[T]] =
      Option(av)
        .filter(x => !Boolean.unbox(x.nul()))
        .map(f.read(_).map(Some(_)))
        .getOrElse(Right(Option.empty[T]))

    def write(t: Option[T]): AttributeValue = t.map(f.write).getOrElse(AttributeValue.builder().nul(true).build())
    override val default = Some(None)
  }

  /**
    * This ensures that if, for instance, you specify an update with Some(5) rather
    * than making the type of `Option` explicit, it doesn't fall back to auto-derivation
    */
  implicit def someFormat[T](implicit f: DynamoFormat[T]): DynamoFormat[Some[T]] = new DynamoFormat[Some[T]] {
    def read(av: AttributeValue): Either[DynamoReadError, Some[T]] =
      Option(av).map(f.read(_).map(Some(_))).getOrElse(Left[DynamoReadError, Some[T]](MissingProperty))

    def write(t: Some[T]): AttributeValue = f.write(t.get)
  }
}
