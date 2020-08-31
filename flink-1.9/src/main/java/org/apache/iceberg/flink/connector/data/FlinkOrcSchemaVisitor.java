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

package org.apache.iceberg.flink.connector.data;

import java.util.List;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.iceberg.Schema;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

abstract class FlinkOrcSchemaVisitor<T> {
  static <T> T visit(LogicalType flinkType, Schema schema, FlinkOrcSchemaVisitor<T> visitor) {
    return visit(flinkType, schema.asStruct(), visitor);
  }

  private static <T> T visit(LogicalType flinkType, Type iType, FlinkOrcSchemaVisitor<T> visitor) {
    switch (iType.typeId()) {
      case STRUCT: {
        return visitRecord(flinkType, iType.asStructType(), visitor);
      }
      case LIST: {
        ArrayType flinkArrayType = (ArrayType) flinkType;
        Types.ListType iListType = iType.asListType();

        T element = visit(flinkArrayType.getElementType(), iListType.elementType(), visitor);

        return visitor.list(iListType, element, flinkArrayType.getElementType());
      }
      case MAP: {
        MapType flinkMapType = (MapType) flinkType;
        Types.MapType iMapType = iType.asMapType();

        T key = visit(flinkMapType.getKeyType(), iMapType.keyType(), visitor);
        T value = visit(flinkMapType.getValueType(), iMapType.valueType(), visitor);

        return visitor.map(iMapType, key, value, flinkMapType.getKeyType(), flinkMapType.getValueType());
      }
      default:
        return visitor.primitive(iType.asPrimitiveType(), flinkType);
    }
  }

  private static <T> T visitRecord(LogicalType flinkType, Types.StructType iStruct, FlinkOrcSchemaVisitor<T> visitor) {
    RowType flinkRowType = (RowType) flinkType;
    int fieldSize = iStruct.fields().size();
    List<T> results = Lists.newArrayListWithExpectedSize(fieldSize);
    List<LogicalType> fieldTypes = Lists.newArrayListWithExpectedSize(fieldSize);
    List<Types.NestedField> nestedFields = iStruct.fields();

    for (int i = 0; i < fieldSize; i++) {
      Types.NestedField iField = nestedFields.get(i);
      int fieldIndex = flinkRowType.getFieldIndex(iField.name());
      LogicalType fieldFlinkType = fieldIndex >= 0 ? flinkRowType.getTypeAt(fieldIndex) : null;

      fieldTypes.add(fieldFlinkType);
      results.add(visit(fieldFlinkType, iField.type(), visitor));

    }
    return visitor.record(iStruct, results, fieldTypes);

  }

  public T record(Types.StructType iStruct, List<T> results, List<LogicalType> fieldTypes) {
    return null;
  }

  public T list(Types.ListType iList, T element, LogicalType elementType) {
    return null;
  }

  public T map(Types.MapType iMap, T key, T value, LogicalType keyType, LogicalType valueType) {
    return null;
  }

  public T primitive(Type.PrimitiveType iPrimitive, LogicalType flinkPrimitive) {
    return null;
  }
}