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

package org.apache.tajo.cli;

import org.apache.tajo.TpchTestBase;
import org.apache.tajo.conf.TajoConf;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertTrue;

public class TestHdfsCommand {
  @Test
  public void testHdfCommand() throws Exception {
    TajoConf tajoConf = TpchTestBase.getInstance().getTestingCluster().getConfiguration();

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    System.setOut(new PrintStream(out));
    System.setErr(new PrintStream(out));
    TajoCli cli = new TajoCli(tajoConf, new String[]{}, null, out);

    cli.executeMetaCommand("\\dfs -test");
    String consoleResult = new String(out.toByteArray());
    String helpMessage = "-test: Not enough arguments: expected 1 but got 0\n" +
        "Usage: hadoop fs [generic options] -test";
    assertTrue(consoleResult, consoleResult.startsWith(helpMessage));
  }
}
