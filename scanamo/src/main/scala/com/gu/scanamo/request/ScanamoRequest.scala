package org.scanamo.request

import org.scanamo.EvaluationKey
import org.scanamo.query.{Condition, Query}
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

case class ScanamoPutRequest(
  tableName: String,
  item: AttributeValue,
  condition: Option[RequestCondition]
)

case class ScanamoDeleteRequest(
  tableName: String,
  key: Map[String, AttributeValue],
  condition: Option[RequestCondition]
)

case class ScanamoUpdateRequest(
  tableName: String,
  key: Map[String, AttributeValue],
  updateExpression: String,
  attributeNames: Map[String, String],
  attributeValues: Map[String, AttributeValue],
  condition: Option[RequestCondition]
)

case class ScanamoScanRequest(
  tableName: String,
  index: Option[String],
  options: ScanamoQueryOptions
)

case class ScanamoQueryRequest(
  tableName: String,
  index: Option[String],
  query: Query[_],
  options: ScanamoQueryOptions
)

case class ScanamoQueryOptions(
  consistent: Boolean,
  limit: Option[Int],
  exclusiveStartKey: Option[EvaluationKey],
  filter: Option[Condition[_]]
)
object ScanamoQueryOptions {
  val default = ScanamoQueryOptions(false, None, None, None)
}

case class RequestCondition(
  expression: String,
  attributeNames: Map[String, String],
  attributeValues: Option[Map[String, AttributeValue]]
)
