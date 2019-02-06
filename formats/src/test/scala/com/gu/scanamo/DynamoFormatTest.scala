package org.scanamo

import scala.collection.JavaConverters._
import scala.reflect.runtime.universe._
import org.scalacheck._
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scanamo.auto._
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest, PutItemRequest, ScalarAttributeType}

class DynamoFormatTest extends FunSpec with Matchers with GeneratorDrivenPropertyChecks {

  // Test that an arbitrary DynamoFormat can be written to dynamo, and then read, producing the same result
  def testReadWrite[A: DynamoFormat: TypeTag](gen: Gen[A]): Unit = {
    val typeLabel = typeTag[A].tpe.toString
    it(s"should write and then read a $typeLabel from dynamo") {
      val client = LocalDynamoDB.client()
      LocalDynamoDB.usingRandomTable(client)('name -> ScalarAttributeType.S) { t =>
        final case class Person(name: String, item: A)
        forAll(gen) { a: A =>
          val person = Person("bob", a)

          val putReq = PutItemRequest.builder()
            .tableName(t).item(DynamoFormat[Person].write(person).m()).build()
          client.putItem(putReq)

          val getReq = GetItemRequest.builder().tableName(t).key(Map("name" -> AttributeValue.builder().s("bob").build()).asJava).build()
          val resp = client.getItem(getReq)
          DynamoFormat[Person].read(AttributeValue.builder().m(resp.item()).build()) shouldBe Right(person)
        }
      }
    }
  }

  def testReadWrite[A: DynamoFormat: TypeTag]()(implicit arb: Arbitrary[A]): Unit =
    testReadWrite(arb.arbitrary)

  testReadWrite[Set[Int]]()
  testReadWrite[Set[Long]]()
  // Generate limited values for double and big decimal
  // see: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html#HowItWorks.DataTypes.Number
  testReadWrite[Set[Double]](Gen.containerOf[Set, Double](Arbitrary.arbLong.arbitrary.map(_.toDouble)))
  testReadWrite[Set[BigDecimal]](
    Gen.containerOf[Set, BigDecimal](Arbitrary.arbLong.arbitrary.map(BigDecimal(_)))
  )
  val nonEmptyStringGen: Gen[String] =
    Gen.nonEmptyContainerOf[Array, Char](Arbitrary.arbChar.arbitrary).map(arr => new String(arr))
  testReadWrite[Set[String]](Gen.containerOf[Set, String](nonEmptyStringGen))
  testReadWrite[Option[String]](Gen.option(nonEmptyStringGen))
  testReadWrite[Option[Int]]()
}
