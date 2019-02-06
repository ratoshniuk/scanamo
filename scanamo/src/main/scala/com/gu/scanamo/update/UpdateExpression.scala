package org.scanamo.update

import cats.data.NonEmptyVector
import cats.kernel.Semigroup
import cats.kernel.instances.map._
import org.scanamo.DynamoFormat
import org.scanamo.query._
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import scala.collection.JavaConverters._

sealed trait UpdateExpression extends Product with Serializable {
  def expression: String =
    typeExpressions.map {
      case (t, e) =>
        s"${t.op} ${e.map(_.expression).toVector.mkString(", ")}"
    }.mkString(" ")
  def typeExpressions: Map[UpdateType, NonEmptyVector[LeafUpdateExpression]]
  def attributeNames: Map[String, String] =
    unprefixedAttributeNames.map {
      case (k, v) => (s"#$k", v)
    }
  val constantValue: Option[(String, AttributeValue)]
  def unprefixedAttributeNames: Map[String, String]
  def attributeValues: Map[String, AttributeValue] =
    (unprefixedAttributeValues ++ constantValue.toMap).map {
      case (k, v) => (s":$k", v)
    }
  def unprefixedAttributeValues: Map[String, AttributeValue]
}

private[update] sealed trait LeafUpdateExpression {
  val updateType: UpdateType
  val attributeNames: Map[String, String]
  val attributeValue: Option[(String, AttributeValue)]
  val constantValue: Option[(String, AttributeValue)]
  def expression: String
  def prefixKeys(prefix: String): LeafUpdateExpression
}

object UpdateExpression {
  def set[V: DynamoFormat](fieldValue: (AttributeName, V)): UpdateExpression =
    SetExpression(fieldValue._1, fieldValue._2)
  def setFromAttribute(fields: (AttributeName, AttributeName)): UpdateExpression = {
    val (to, from) = fields
    SetExpression.fromAttribute(from, to)
  }
  def append[V: DynamoFormat](fieldValue: (AttributeName, V)): UpdateExpression =
    AppendExpression(fieldValue._1, fieldValue._2)
  def prepend[V: DynamoFormat](fieldValue: (AttributeName, V)): UpdateExpression =
    PrependExpression(fieldValue._1, fieldValue._2)
  def appendAll[V: DynamoFormat](fieldValue: (AttributeName, List[V])): UpdateExpression =
    AppendAllExpression(fieldValue._1, fieldValue._2)
  def prependAll[V: DynamoFormat](fieldValue: (AttributeName, List[V])): UpdateExpression =
    PrependAllExpression(fieldValue._1, fieldValue._2)
  def add[V: DynamoFormat](fieldValue: (AttributeName, V)): UpdateExpression =
    AddExpression(fieldValue._1, fieldValue._2)
  def delete[V: DynamoFormat](fieldValue: (AttributeName, V)): UpdateExpression =
    DeleteExpression(fieldValue._1, fieldValue._2)
  def remove(field: AttributeName): UpdateExpression =
    RemoveExpression(field)

  implicit object Semigroup extends Semigroup[UpdateExpression] {
    override def combine(x: UpdateExpression, y: UpdateExpression): UpdateExpression = AndUpdate(x, y)
  }
}

private[update] sealed trait UpdateType { val op: String }
private[update] case object SET extends UpdateType { override val op = "SET" }
private[update] case object ADD extends UpdateType { override val op = "ADD" }
private[update] case object DELETE extends UpdateType { override val op = "DELETE" }
private[update] case object REMOVE extends UpdateType { override val op = "REMOVE" }

private[update] case class SimpleUpdateExpression(leaf: LeafUpdateExpression) extends UpdateExpression {
  override def typeExpressions: Map[UpdateType, NonEmptyVector[LeafUpdateExpression]] =
    Map(leaf.updateType -> NonEmptyVector.of(leaf))
  override def unprefixedAttributeNames: Map[String, String] =
    leaf.attributeNames
  override def unprefixedAttributeValues: Map[String, AttributeValue] =
    leaf.attributeValue.toMap
  override val constantValue: Option[(String, AttributeValue)] = leaf.constantValue
}

private[update] case class LeafSetExpression(
  namePlaceholder: String,
  attributeNames: Map[String, String],
  valuePlaceholder: String,
  av: AttributeValue
) extends LeafUpdateExpression {
  override val updateType = SET
  override val constantValue = None
  override val attributeValue = Some(valuePlaceholder -> av)
  override def expression: String = s"#$namePlaceholder = :$valuePlaceholder"
  override def prefixKeys(prefix: String): LeafUpdateExpression =
    LeafSetExpression(
      namePlaceholder,
      attributeNames,
      s"$prefix$valuePlaceholder",
      av
    )
}

object SetExpression {
  private val prefix = "updateSet"
  def apply[V](field: AttributeName, value: V)(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafSetExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        format.write(value)
      )
    )
  def fromAttribute(from: AttributeName, to: AttributeName): UpdateExpression =
    SimpleUpdateExpression(new LeafUpdateExpression {
      override def expression: String = s"#${to.placeholder(prefix)} = #${from.placeholder(prefix)}"

      override def prefixKeys(prefix: String): LeafUpdateExpression = this

      override val constantValue: Option[(String, AttributeValue)] = None
      override val attributeNames: Map[String, String] = to.attributeNames(prefix) ++ from.attributeNames(prefix)
      override val updateType: UpdateType = SET
      override val attributeValue: Option[(String, AttributeValue)] = None
    })
}

private[update] case class LeafAppendExpression(
  namePlaceholder: String,
  attributeNames: Map[String, String],
  valuePlaceholder: String,
  av: AttributeValue
) extends LeafUpdateExpression {
  override val updateType = SET
  override val constantValue = Some("emptyList" -> AttributeValue.builder().l(List.empty[AttributeValue].asJava).build())
  override val attributeValue = Some(valuePlaceholder -> av)
  override def expression: String =
    s"#$namePlaceholder = list_append(if_not_exists(#$namePlaceholder, :emptyList), :$valuePlaceholder)"
  override def prefixKeys(prefix: String): LeafUpdateExpression =
    LeafAppendExpression(
      namePlaceholder,
      attributeNames,
      s"$prefix$valuePlaceholder",
      av
    )
}

object AppendExpression {
  private val prefix = "updateAppend"
  def apply[V](field: AttributeName, value: V)(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafAppendExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        DynamoFormat.listFormat[V].write(List(value))
      )
    )
}

private[update] case class LeafPrependExpression(
  namePlaceholder: String,
  attributeNames: Map[String, String],
  valuePlaceholder: String,
  av: AttributeValue
) extends LeafUpdateExpression {
  override val updateType = SET
  override val constantValue = Some("emptyList" -> AttributeValue.builder().l(List.empty[AttributeValue].asJava).build())
  override val attributeValue = Some(valuePlaceholder -> av)
  override def expression: String =
    s"#$namePlaceholder = list_append(:$valuePlaceholder, if_not_exists(#$namePlaceholder, :emptyList))"
  override def prefixKeys(prefix: String): LeafUpdateExpression =
    LeafPrependExpression(
      namePlaceholder,
      attributeNames,
      s"$prefix$valuePlaceholder",
      av
    )
}

object PrependExpression {
  private val prefix = "updatePrepend"
  def apply[V](field: AttributeName, value: V)(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafPrependExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        DynamoFormat.listFormat[V].write(List(value))
      )
    )
}

object AppendAllExpression {
  private val prefix = "updateAppendAll"
  def apply[V](field: AttributeName, value: List[V])(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafAppendExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        DynamoFormat.listFormat[V].write(value)
      )
    )
}

object PrependAllExpression {
  private val prefix = "updatePrependAll"
  def apply[V](field: AttributeName, value: List[V])(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafPrependExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        DynamoFormat.listFormat[V].write(value)
      )
    )
}

private[update] case class LeafAddExpression(
  namePlaceholder: String,
  attributeNames: Map[String, String],
  valuePlaceholder: String,
  av: AttributeValue
) extends LeafUpdateExpression {
  override val updateType = ADD
  override val constantValue = None
  override val attributeValue = Some(valuePlaceholder -> av)
  override def expression: String = s"#$namePlaceholder :$valuePlaceholder"
  override def prefixKeys(prefix: String): LeafUpdateExpression =
    LeafAddExpression(
      namePlaceholder,
      attributeNames,
      s"$prefix$valuePlaceholder",
      av
    )
}

object AddExpression {
  private val prefix = "updateSet"
  def apply[V](field: AttributeName, value: V)(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafAddExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        format.write(value)
      )
    )
}

/*
Note the difference between DELETE and REMOVE:
 - DELETE is used to delete an element from a set
 - REMOVE is used to remove an attribute from an item
 */
private[update] case class LeafDeleteExpression(
  namePlaceholder: String,
  attributeNames: Map[String, String],
  valuePlaceholder: String,
  av: AttributeValue
) extends LeafUpdateExpression {
  override val updateType = DELETE
  override val constantValue = None
  override val attributeValue = Some(valuePlaceholder -> av)
  override def expression: String = s"#$namePlaceholder :$valuePlaceholder"
  override def prefixKeys(prefix: String): LeafUpdateExpression =
    LeafDeleteExpression(
      namePlaceholder,
      attributeNames,
      s"$prefix$valuePlaceholder",
      av
    )
}

object DeleteExpression {
  private val prefix = "updateDelete"
  def apply[V](field: AttributeName, value: V)(implicit format: DynamoFormat[V]): UpdateExpression =
    SimpleUpdateExpression(
      LeafDeleteExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix),
        "update",
        format.write(value)
      )
    )
}

private[update] case class LeafRemoveExpression(
  namePlaceholder: String,
  attributeNames: Map[String, String]
) extends LeafUpdateExpression {
  override val updateType = REMOVE
  override val constantValue = None
  override val attributeValue = None
  override def expression: String = s"#$namePlaceholder"
  override def prefixKeys(prefix: String): LeafUpdateExpression =
    LeafRemoveExpression(
      namePlaceholder,
      attributeNames
    )
}

object RemoveExpression {
  private val prefix = "updateRemove"
  def apply(field: AttributeName): UpdateExpression =
    SimpleUpdateExpression(
      LeafRemoveExpression(
        field.placeholder(prefix),
        field.attributeNames(prefix)
      )
    )
}

case class AndUpdate(l: UpdateExpression, r: UpdateExpression) extends UpdateExpression {

  def prefixKeys[T](map: Map[String, T], prefix: String) = map.map {
    case (k, v) => (s"$prefix$k", v)
  }

  private val semigroup = Semigroup[Map[UpdateType, NonEmptyVector[LeafUpdateExpression]]]

  override def typeExpressions: Map[UpdateType, NonEmptyVector[LeafUpdateExpression]] = {
    val leftUpdates = l.typeExpressions.mapValues(_.map(_.prefixKeys("l_")))
    val rightUpdates = r.typeExpressions.mapValues(_.map(_.prefixKeys("r_")))

    semigroup.combine(leftUpdates, rightUpdates)
  }

  override val constantValue: Option[(String, AttributeValue)] = l.constantValue.orElse(r.constantValue)

  override def unprefixedAttributeNames: Map[String, String] =
    l.unprefixedAttributeNames ++ r.unprefixedAttributeNames

  override def unprefixedAttributeValues: Map[String, AttributeValue] =
    prefixKeys(l.unprefixedAttributeValues, "l_") ++ prefixKeys(r.unprefixedAttributeValues, "r_")
}
