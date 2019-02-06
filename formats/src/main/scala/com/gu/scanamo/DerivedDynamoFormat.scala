package org.scanamo

import cats.data.{NonEmptyList, Validated}
import cats.syntax.either._
import org.scanamo.error._
import org.scanamo.export.Exported
import shapeless._
import shapeless.labelled._
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import collection.JavaConverters._

trait DerivedDynamoFormat {
  type ValidatedPropertiesError[T] = Validated[InvalidPropertiesError, T]
  type NotSymbol[T] = |¬|[Symbol]#λ[T]

  trait ConstructedDynamoFormat[T] {
    def read(av: AttributeValue): Validated[InvalidPropertiesError, T]
    def write(t: T): AttributeValue
  }

  trait InvalidConstructedDynamoFormat[T] extends ConstructedDynamoFormat[T]
  trait ValidConstructedDynamoFormat[T] extends ConstructedDynamoFormat[T]

  implicit val hnil: InvalidConstructedDynamoFormat[HNil] =
    new InvalidConstructedDynamoFormat[HNil] {
      def read(av: AttributeValue) = Validated.valid(HNil)
      def write(t: HNil): AttributeValue = {
        AttributeValue.builder().m(Map.empty[String, AttributeValue].asJava).build()
      }
    }

  implicit def hcons[K <: Symbol, V, T <: HList](
    implicit
    headFormat: Lazy[DynamoFormat[V]],
    tailFormat: Lazy[ConstructedDynamoFormat[T]],
    fieldWitness: Witness.Aux[K]
  ): ValidConstructedDynamoFormat[FieldType[K, V] :: T] =
    new ValidConstructedDynamoFormat[FieldType[K, V] :: T] {
      def read(av: AttributeValue): Validated[InvalidPropertiesError, FieldType[K, V] :: T] = {
        val fieldName = fieldWitness.value.name

        val possibleValue =
          av.m().asScala.get(fieldName).map(headFormat.value.read).orElse(headFormat.value.default.map(Either.right))

        val valueOrError = possibleValue.getOrElse(Either.left[DynamoReadError, V](MissingProperty))

        def validatedProperty(x: Either[DynamoReadError, V]): Validated[InvalidPropertiesError, V] =
          x.leftMap(e => InvalidPropertiesError(NonEmptyList(PropertyReadError(fieldName, e), Nil))).toValidated

        val head: Validated[InvalidPropertiesError, FieldType[K, V]] = validatedProperty(valueOrError).map(field[K](_))
        val tail = tailFormat.value.read(av)

        cats.Apply[ValidatedPropertiesError].map2(head, tail)(_ :: _)
      }
      def write(t: FieldType[K, V] :: T): AttributeValue = {
        val tailValue = tailFormat.value.write(t.tail)
        val av = headFormat.value.write(t.head)
        if (!(av.nul() eq null) && av.nul())
          tailValue
        else {
          val content = (tailValue.m().asScala + (fieldWitness.value.name -> av)).asJava
          AttributeValue.builder().m(content).build()
        }
      }
    }

  trait CoProductDynamoFormat[T] extends DynamoFormat[T]

  implicit val cnil: CoProductDynamoFormat[CNil] = new CoProductDynamoFormat[CNil] {
    def read(av: AttributeValue): Either[DynamoReadError, CNil] =
      Left(TypeCoercionError(new Exception(s"$av was not of the expected type")))

    def write(t: CNil): AttributeValue = sys.error("CNil cannot be written to an AttributeValue")
  }

  implicit def coproduct[K <: Symbol, V, T <: Coproduct](
    implicit
    headFormat: Lazy[DynamoFormat[V]],
    tailFormat: CoProductDynamoFormat[T],
    fieldWitness: Witness.Aux[K]
  ): CoProductDynamoFormat[FieldType[K, V] :+: T] = {
    val fieldName = fieldWitness.value.name
    new CoProductDynamoFormat[FieldType[K, V] :+: T] {
      def read(av: AttributeValue): Either[DynamoReadError, FieldType[K, V] :+: T] =
        av.m().asScala.get(fieldName) match {
          case Some(nestedAv) =>
            val value = headFormat.value.read(nestedAv)
            value.map(v => Inl(field[K](v)))
          case None =>
            tailFormat.read(av).map(v => Inr(v))
        }

      def write(field: FieldType[K, V] :+: T): AttributeValue = field match {
        case Inl(h) =>
          AttributeValue.builder().m(Map(fieldName -> headFormat.value.write(h)).asJava).build()
        case Inr(t) =>
          tailFormat.write(t)
      }
    }
  }

  implicit def genericProduct[T: NotSymbol, R](
    implicit gen: LabelledGeneric.Aux[T, R],
    formatR: Lazy[ValidConstructedDynamoFormat[R]]
  ): Exported[DynamoFormat[T]] =
    Exported(
      new DynamoFormat[T] {
        def read(av: AttributeValue): Either[DynamoReadError, T] = formatR.value.read(av).map(gen.from).toEither
        def write(t: T): AttributeValue = formatR.value.write(gen.to(t))
      }
    )

  implicit def genericCoProduct[T, R](
    implicit gen: LabelledGeneric.Aux[T, R],
    formatR: Lazy[CoProductDynamoFormat[R]]
  ): Exported[DynamoFormat[T]] =
    Exported(
      new DynamoFormat[T] {
        def read(av: AttributeValue): Either[DynamoReadError, T] = formatR.value.read(av).map(gen.from)
        def write(t: T): AttributeValue = formatR.value.write(gen.to(t))
      }
    )
}
