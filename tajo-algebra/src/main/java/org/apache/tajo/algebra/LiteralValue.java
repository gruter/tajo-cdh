/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.algebra;

import com.google.common.base.Objects;

public class LiteralValue extends Expr {
  protected LiteralType valueType;
  protected String value;

  public static enum LiteralType {
    Boolean,
    String,
    Unsigned_Integer,
    Unsigned_Float,
    Unsigned_Large_Integer,
  }

  public LiteralValue(String value, LiteralType valueType) {
    super(OpType.Literal);
    this.value = value;
    this.valueType = valueType;
  }

  public LiteralType getValueType() {
    return this.valueType;
  }

  public String getValue() {
    return this.value;
  }

  public int hashCode() {
    return Objects.hashCode(valueType, value);
  }

  public boolean equalsTo(Expr expr) {
    LiteralValue another = (LiteralValue) expr;
    boolean a = valueType.equals(another.valueType);
    boolean b =  value.equals(another.value);

    return a && b;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder(valueType == LiteralType.String ? "'" + value + "'" : value);
    sb.append("(").append(valueType).append(")");
    return sb.toString();
  }
}
