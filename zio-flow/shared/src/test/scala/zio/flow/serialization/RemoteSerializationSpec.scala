/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.serialization

import zio.flow._
import zio.schema.ast.SchemaAst
import zio.schema.codec.{Codec, JsonCodec, ProtobufCodec}
import zio.schema.{DeriveSchema, Schema}
import zio.test._
import zio.{ZIO, ZNothing}

object RemoteSerializationSpec extends ZIOSpecDefault with Generators {
  override def spec: Spec[TestEnvironment, Any] =
    suite("Remote serialization")(
      suite("roundtrip equality")(
        equalityWithCodec(JsonCodec),
        equalityWithCodec(ProtobufCodec)
      ),
      test("Remote schema is serializable") {
        val schema             = Remote.schemaAny
        val serialized         = JsonCodec.encode(SchemaAst.schema)(schema.ast)
        val deserialized       = JsonCodec.decode(SchemaAst.schema)(serialized)
        val deserializedSchema = deserialized.map(_.toSchema)
        assertTrue(
          Schema.structureEquality.equal(schema, deserializedSchema.toOption.get)
        )
      } @@ TestAspect.ignore // TODO: fix recursion
    )

  case class TestCaseClass(a: String, b: Int)
  object TestCaseClass {
    val gen: Gen[Sized, TestCaseClass] =
      for {
        a <- Gen.string
        b <- Gen.int
      } yield TestCaseClass(a, b)

    implicit val schema: Schema[TestCaseClass] = DeriveSchema.gen
  }

  private def equalityWithCodec(
    codec: Codec
  ): Spec[Sized with TestConfig, String] =
    suite(codec.getClass.getSimpleName)(
      test("literal") {
        check(genLiteral) { literal =>
          roundtrip(codec, literal)
        }
      },
      test("fail")(roundtripCheck(codec, genFail)),
      test("ignore") {
        roundtrip(codec, Remote.Ignore())
      },
      test("flow")(roundtripCheck(codec, genRemoteFlow)),
      test("nested")(roundtripCheck(codec, genNested)),
      test("variable reference")(roundtripCheck(codec, genVariableReference)),
      test("variable")(roundtripCheck(codec, genRemoteVariable)),
      test("variable of nothing") {
        val variable = Remote.Variable[ZNothing](RemoteVariableName("test"))
        roundtrip(codec, variable)
      },
      test("binary")(roundtripCheck(codec, genBinary)),
      test("unary")(roundtripCheck(codec, genUnary)),
      test("unbound remote function")(roundtripCheck(codec, genUnboundRemoteFunction)),
      test("bind")(roundtripCheck(codec, genBind)),
      test("remote either")(roundtripCheck(codec, genRemoteEither)),
      test("foldEither")(roundtripCheck(codec, genFoldEither)),
      test("try")(roundtripCheck(codec, genTry)),
      test("tuple2")(roundtripCheck(codec, genTuple2)),
      test("tuple3")(roundtripCheck(codec, genTuple3)),
      test("tuple4")(roundtripCheck(codec, genTuple4)),
      test("tupleAccess")(roundtripCheck(codec, genTupleAccess)),
      test("branch")(roundtripCheck(codec, genBranch)),
      test("equal")(roundtripCheck(codec, genEqual)),
      test("fold")(roundtripCheck(codec, genFold)),
      test("cons")(roundtripCheck(codec, genCons)),
      test("uncons")(roundtripCheck(codec, genUnCons)),
      test("remote some")(roundtripCheck(codec, genRemoteSome)),
      test("fold option")(roundtripCheck(codec, genFoldOption)),
      test("recurse")(roundtripCheck(codec, genRecurse)),
      test("recurseWith")(roundtripCheck(codec, genRecurseWith)),
      test("listToSet")(roundtripCheck(codec, genListToSet)),
      test("setToList")(roundtripCheck(codec, genSetToList)),
      test("listToString")(roundtripCheck(codec, genListToString)),
      test("stringToCharList")(roundtripCheck(codec, genStringToCharList)),
      test("charListToString")(roundtripCheck(codec, genCharListToString)),
      test("opticGet")(roundtripCheck(codec, genOpticGet)),
      test("opticSet")(roundtripCheck(codec, genOpticSet))
    )

  private def roundtripCheck(
    codec: Codec,
    gen: Gen[Sized, Remote[Any]]
  ): ZIO[Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: Codec, value: Remote[Any]): TestResult = {
    val encoded = codec.encode(Remote.schemaAny)(value)
    val decoded = codec.decode(Remote.schemaAny)(encoded)

    //    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }
}
