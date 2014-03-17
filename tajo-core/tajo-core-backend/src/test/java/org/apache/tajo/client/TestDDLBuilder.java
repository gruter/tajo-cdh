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

package org.apache.tajo.client;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.partition.PartitionMethodDesc;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.common.TajoDataTypes;
import org.apache.tajo.util.FileUtil;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;


public class TestDDLBuilder {
  @Test
  public void testBuildDDL() throws Exception {
    Schema schema = new Schema();
    schema.addColumn("name", TajoDataTypes.Type.BLOB);
    schema.addColumn("addr", TajoDataTypes.Type.TEXT);
    TableMeta meta = CatalogUtil.newTableMeta(CatalogProtos.StoreType.CSV);
    meta.putOption(CatalogConstants.CSVFILE_DELIMITER, CatalogConstants.CSVFILE_DELIMITER_DEFAULT);
    meta.putOption(CatalogConstants.COMPRESSION_CODEC, GzipCodec.class.getName());

    TableDesc desc = new TableDesc("table1", schema, meta, new Path("/table1"));

    Schema expressionSchema = new Schema();
    expressionSchema.addColumn("key", TajoDataTypes.Type.INT4);
    expressionSchema.addColumn("key2", TajoDataTypes.Type.TEXT);
    PartitionMethodDesc partitionMethod = new PartitionMethodDesc(
        "table1",
        CatalogProtos.PartitionType.COLUMN,
        "key,key2",
        expressionSchema);
    desc.setPartitionMethod(partitionMethod);

    assertEquals(FileUtil.readTextFile(new File("src/test/resources/results/testBuildDDL.result")),
        DDLBuilder.buildDDL(desc));
  }
}
