/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.spark.sql.iceberg;

import org.apache.spark.sql.catalyst.analysis.{CastSupport, Resolver}
import org.apache.spark.sql.catalyst.expressions.{Alias, CreateNamedStruct, Expression, GetStructField, Literal,
  NamedExpression}
import org.apache.spark.sql.types.{DataType, NullType, StructType}

trait UpdateExpressionsSupport3 extends CastSupport{

  case class UpdateOperation(targetColNameParts: Seq[String], updateExpr: Expression)

  def generateUpdateExpressions(targetCols: Seq[NamedExpression],
                                nameParts: Seq[Seq[String]],
                                updateExprs: Seq[Expression],
                                resolver: Resolver): Seq[Expression] = {
    assert(nameParts.size == updateExprs.size)
    val updateOps = nameParts.zip(updateExprs).map {
      case (nameParts, expr) => UpdateOperation(nameParts, expr)
    }
    generateUpdateExpressions(targetCols, updateOps, resolver)
  }

  protected def castIfNeeded(child: Expression,
                             dataType: DataType): Expression = {
    child match {
      // Need to deal with NullType here, as some types cannot be casted from NullType, e.g.,
      // StructType.
      case Literal(nul, NullType) => Literal(nul, dataType)
      case _: Any => if (child.dataType != dataType) cast(child, dataType) else child
    }
  }

  /**
   * Given a list of target-column expressions and a set of update operations, generate a list
   * of update expressions, which are aligned with given target-column expressions.
   *
   * For update operations to nested struct fields, this method recursively walks down schema tree
   * and apply the update expressions along the way.
   * For example, assume table `target` has two attributes a and z, where a is of struct type
   * with 3 fields: b, c and d, and z is of integer type.
   *
   * Given an update command:
   *
   *  - UPDATE target SET a.b = 1, a.c = 2, z = 3
   *
   * this method works as follows:
   *
   * generateUpdateExpressions(targetCols=[a,z], updateOps=[(a.b, 1), (a.c, 2), (z, 3)])
   *   generateUpdateExpressions(targetCols=[b,c,d], updateOps=[(b, 1),(c, 2)], pathPrefix=["a"])
   *     end-of-recursion
   *   -> returns (1, 2, d)
   * -> return ((1, 2, d), 3)
   *
   * @param targetCols a list of expressions to read named columns; these named columns can be
   *                   either the top-level attributes of a table, or the nested fields of a
   *                   StructType column.
   * @param updateOps a set of update operations.
   * @param pathPrefix the path from root to the current (nested) column. Only used for printing out
   *                   full column path in error messages.
   */
  // scalastyle:off method.length
  protected def generateUpdateExpressions(targetCols: Seq[NamedExpression],
                                          updateOps: Seq[UpdateOperation],
                                          resolver: Resolver,
                                          pathPrefix: Seq[String] = Nil): Seq[Expression] = {
    updateOps.foreach { u =>
      if (!targetCols.exists(f => resolver(f.name, u.targetColNameParts.head))) {
        throw new IllegalArgumentException("column not found" + (pathPrefix :+ u.targetColNameParts.head).mkString("."))
      }
    }

    // Transform each targetCol to a possibly updated expression
    targetCols.map { targetCol =>
      // The prefix of a update path matches the current targetCol path.
      val prefixMatchedOps = updateOps.filter(u => resolver(u.targetColNameParts.head, targetCol.name))
      // No prefix matches this target column, return its original expression.
      if (prefixMatchedOps.isEmpty) {
        targetCol
      } else {
        // The update operation whose path exactly matches the current targetCol path.
        val fullyMatchedOp = prefixMatchedOps.find(_.targetColNameParts.size == 1)
        if (fullyMatchedOp.isDefined) {
          if (prefixMatchedOps.size > 1) {
            throw new IllegalArgumentException("there is a conflict from these SET columns: "
              + prefixMatchedOps.map(op => (pathPrefix ++ op.targetColNameParts).mkString(".")))
          }
          // For an exact match, return the updateExpr from the update operation.
          castIfNeeded(fullyMatchedOp.get.updateExpr, targetCol.dataType)
        } else {
          targetCol.dataType match {
            case StructType(fields) =>
              val fieldExpr = targetCol
              val childExprs = fields.zipWithIndex.map { case (field, ordinal) =>
                Alias(GetStructField(fieldExpr, ordinal, Some(field.name)), field.name)()
              }
              // Recursively apply update operations to the children
              val updatedChildExprs = generateUpdateExpressions(
                childExprs,
                prefixMatchedOps.map(u => u.copy(targetColNameParts = u.targetColNameParts.tail)),
                resolver,
                pathPrefix :+ targetCol.name)
              // Reconstruct the expression for targetCol using its possibly updated children
              val namedStructExprs = fields
                .zip(updatedChildExprs)
                .flatMap { case (field, expr) => Seq(Literal(field.name), expr) }
              CreateNamedStruct(namedStructExprs)

            case otherType: Any => throw new IllegalArgumentException("Updating nested fields is only supported for" +
              " StructType, but you are trying to update " + otherType)
          }
        }
      }
    }
  }
  // scalastyle:on method.length
}
