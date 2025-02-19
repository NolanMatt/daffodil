/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.daffodil.core.grammar

import scala.xml.Elem
import scala.xml.Null
import scala.xml.UnprefixedAttribute

import org.apache.daffodil.core.dsom.ElementBase
import org.apache.daffodil.core.dsom.GlobalSimpleTypeDef
import org.apache.daffodil.core.dsom.RepTypeQuasiElementDecl
import org.apache.daffodil.lib.exceptions.Assert
import org.apache.daffodil.runtime1.dpath.LE_Byte
import org.apache.daffodil.runtime1.dpath.LE_Int
import org.apache.daffodil.runtime1.dpath.LE_Integer
import org.apache.daffodil.runtime1.dpath.LE_Long
import org.apache.daffodil.runtime1.dpath.LE_NonNegativeInteger
import org.apache.daffodil.runtime1.dpath.LE_Short
import org.apache.daffodil.runtime1.dpath.LE_UnsignedByte
import org.apache.daffodil.runtime1.dpath.LE_UnsignedInt
import org.apache.daffodil.runtime1.dpath.LE_UnsignedLong
import org.apache.daffodil.runtime1.dpath.LE_UnsignedShort
import org.apache.daffodil.runtime1.dpath.LT_Byte
import org.apache.daffodil.runtime1.dpath.LT_Int
import org.apache.daffodil.runtime1.dpath.LT_Integer
import org.apache.daffodil.runtime1.dpath.LT_Long
import org.apache.daffodil.runtime1.dpath.LT_NonNegativeInteger
import org.apache.daffodil.runtime1.dpath.LT_Short
import org.apache.daffodil.runtime1.dpath.LT_UnsignedByte
import org.apache.daffodil.runtime1.dpath.LT_UnsignedInt
import org.apache.daffodil.runtime1.dpath.LT_UnsignedLong
import org.apache.daffodil.runtime1.dpath.LT_UnsignedShort
import org.apache.daffodil.runtime1.dpath.NodeInfo
import org.apache.daffodil.runtime1.dpath.NumberCompareOp
import org.apache.daffodil.runtime1.infoset.DataValue.DataValueNumber
import org.apache.daffodil.runtime1.infoset.DataValue.DataValueString

trait RepTypeMixin { self: ElementBase =>

  lazy val hasRepType: Boolean = LV('hasRepType) {
    val hasRT = isSimpleType && findPropertyOption("repType").isDefined
    schemaDefinitionWhen(
      hasRT && isOutputValueCalc,
      "dfdl:outputValueCalc and dfdlx:repType cannot be defined on the same element",
    )
    hasRT
  }.value

  private lazy val repTypeGSTD: GlobalSimpleTypeDef = LV('prefixLengthTypeGSTD) {
    // throws an SDE if the simple type def is not found or if it is not a simple type (e.g. a
    // primitive type)
    val gstd = schemaSet.getGlobalSimpleTypeDefNoPrim(repType, "dfdlx:repType", this)
    schemaDefinitionUnless(
      gstd.primType.isInstanceOf[NodeInfo.Integer.Kind],
      "dfdlx:repType (%s) must resolve to a global simple type definition derived from xs:integer, but was %s",
      repType,
      gstd.primType.globalQName,
    )
    gstd
  }.value

  lazy val repTypeElementDecl: RepTypeQuasiElementDecl = LV('repTypeElementDecl) {
    val xmlElem = Elem(
      null,
      "QuasiElementForRepType",
      new UnprefixedAttribute("type", repType.toString, Null),
      namespaces,
      true,
    )
    RepTypeQuasiElementDecl(xmlElem, repTypeGSTD)
  }.value

  lazy val optRepTypeElementDecl: Option[RepTypeQuasiElementDecl] = LV('optRepTypeElementDecl) {
    if (hasRepType) Some(repTypeElementDecl) else None
  }.value

  lazy val (
    repTypeCompareLT: NumberCompareOp,
    repTypeCompareLE: NumberCompareOp,
  ) = LV('repTypeComparers) {
    repTypeElementDecl.primType match {
      case NodeInfo.Integer => (LT_Integer, LE_Integer)
      case NodeInfo.Long => (LT_Long, LE_Long)
      case NodeInfo.Int => (LT_Int, LE_Int)
      case NodeInfo.Short => (LT_Short, LE_Short)
      case NodeInfo.Byte => (LT_Byte, LE_Byte)
      case NodeInfo.NonNegativeInteger => (LT_NonNegativeInteger, LE_NonNegativeInteger)
      case NodeInfo.UnsignedLong => (LT_UnsignedLong, LE_UnsignedLong)
      case NodeInfo.UnsignedInt => (LT_UnsignedInt, LE_UnsignedInt)
      case NodeInfo.UnsignedShort => (LT_UnsignedShort, LE_UnsignedShort)
      case NodeInfo.UnsignedByte => (LT_UnsignedByte, LE_UnsignedByte)
      // $COVERAGE-OFF$
      case _ => Assert.invariantFailed("repType should only be an integer type")
      // $COVERAGE-ON$
    }
  }.value

  lazy val (
    repTypeParseValuesMap: Map[DataValueNumber, DataValueString],
    repTypeParseRangesMap: Seq[(DataValueNumber, DataValueNumber, DataValueString)],
    repTypeUnparseMap: Map[DataValueString, DataValueNumber],
  ) = LV('repTypeMaps) {

    schemaDefinitionUnless(
      primType.isInstanceOf[NodeInfo.String.Kind],
      "Type must derive from xs:string when dfdlx:repType (%s) is defined: %s",
      repType,
      primType.globalQName,
    )

    schemaDefinitionWhen(
      typeDef.optRestriction.isEmpty,
      "A restriction is required when dfdlx:repType (%s) is defined",
      repType,
    )

    val restriction = typeDef.optRestriction.get

    schemaDefinitionWhen(
      restriction.enumerations.isEmpty,
      "There must be at least one enumeration defined when dfdlx:repType (%s) is defined",
      repType,
    )

    val lt = repTypeCompareLT

    // This is Seq of attributes extracted from each enumeration, where each item in the
    // sequence is a 3-tuple containing the cooked repValues, repValueRanges, and enum value
    // from the associated enum
    val enumAttributes = restriction.enumerations.map { enum =>
      enum.schemaDefinitionWhen(
        enum.repValuesRaw.isEmpty && enum.repValueRangesRaw.isEmpty,
        "All enumerations must define dfdlx:repValues and/or dfdlx:repValueRanges when dfdlx:repType (%s) is defined",
        repType,
      )

      val repValues: Seq[DataValueNumber] =
        enum.repValuesRaw
          .map(repTypeElementDecl.primType.fromXMLString(_).asInstanceOf[DataValueNumber])
      val repValueRanges: Seq[(DataValueNumber, DataValueNumber)] =
        enum.repValueRangesRaw
          .map(repTypeElementDecl.primType.fromXMLString(_).asInstanceOf[DataValueNumber])
          .sliding(2, 2)
          .map { case Seq(low, high) =>
            enum.schemaDefinitionUnless(
              lt.operate(low, high).getBoolean,
              "dfdlx:repValueRanges low value (%s) must be less than high value (%s)",
              low.value,
              high.value,
            )
            (low, high)
          }
          .toSeq
      val enumValue = enum.enumValueCooked.asInstanceOf[DataValueString]
      (repValues, repValueRanges, enumValue)
    }

    // combine all rep values and ranges into a single seq of low/high tuples, sort them by
    // their low value, and look for overlaps
    enumAttributes
      .flatMap { case (repValues, repRanges, _) => repValues.map(v => (v, v)) ++ repRanges }
      .sortWith { case ((l1, _), (l2, _)) => lt.operate(l1, l2).getBoolean }
      .reduce[(DataValueNumber, DataValueNumber)] { case ((l1, h1), (l2, h2)) =>
        restriction.schemaDefinitionUnless(
          lt.operate(h1, l2).getBoolean,
          "Overlapping dfdlx:%s (%s) and dfdlx:%s (%s) found",
          if (lt.operate(l1, h1).getBoolean) "repValueRanges" else "repValues",
          if (lt.operate(l1, h1).getBoolean) l1.value + " " + h1.value else l1.value,
          if (lt.operate(l2, h2).getBoolean) "repValueRanges" else "repValues",
          if (lt.operate(l2, h2).getBoolean) l2.value + " " + h2.value else l2.value,
        )
        (l2, h2)
      }

    // generate the data structures for fast mapping of rep values to/from logical values
    val parseValuesMap: Map[DataValueNumber, DataValueString] = enumAttributes
      .filterNot(_._1.isEmpty)
      .flatMap { case (repValues, _, enumValue) =>
        repValues.map { repValue => (repValue, enumValue) }
      }
      .toMap
    val parseRangesMap: Seq[(DataValueNumber, DataValueNumber, DataValueString)] =
      enumAttributes
        .filterNot(_._2.isEmpty)
        .flatMap { case (_, repRanges, enumValue) =>
          repRanges.map { case (low, high) => (low, high, enumValue) }
        }
    val unparseMap: Map[DataValueString, DataValueNumber] = enumAttributes.map {
      case (repValues, repRanges, enumValue) =>
        val canonicalRep = repValues.headOption
          .orElse(repRanges.headOption.map(_._1))
          .getOrElse(
            // $COVERAGE-OFF$
            Assert.invariantFailed("Must have at least one of repValues or repValuesRanges"),
            // $COVERAGE-ON$
          )
        (enumValue, canonicalRep)
    }.toMap
    (parseValuesMap, parseRangesMap, unparseMap)
  }.value

}
