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

import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.FunctionDesc;
import org.apache.tajo.catalog.proto.CatalogProtos;

import java.util.*;

public class DescFunctionCommand extends TajoShellCommand {
  public DescFunctionCommand(TajoCli.TajoCliContext context) {
    super(context);
  }

  @Override
  public String getCommand() {
    return "\\df";
  }

  @Override
  public void invoke(String[] cmd) throws Exception {
    boolean printDetail = false;
    String functionName = "";
    if(cmd.length == 0) {
      throw new IllegalArgumentException();
    }

    if (cmd.length == 2) {
      printDetail = true;
      functionName = cmd[1];
    }

    List<CatalogProtos.FunctionDescProto> functions =
        new ArrayList<CatalogProtos.FunctionDescProto>(client.getFunctions(functionName));

    Collections.sort(functions, new Comparator<CatalogProtos.FunctionDescProto>() {
      @Override
      public int compare(CatalogProtos.FunctionDescProto f1, CatalogProtos.FunctionDescProto f2) {
        int nameCompared = f1.getSignature().compareTo(f2.getSignature());
        if (nameCompared != 0) {
          return nameCompared;
        } else {
          return f1.getReturnType().getType().compareTo(f2.getReturnType().getType());
        }
      }
    });

    String[] headers = new String[]{"Name", "Result type", "Argument types", "Description", "Type"};
    float[] columnWidthRates = new float[]{0.15f, 0.15f, 0.2f, 0.4f, 0.1f};
    int[] columnWidths = printHeader(headers, columnWidthRates);

    for(CatalogProtos.FunctionDescProto eachFunction: functions) {
      String name = eachFunction.getSignature();
      String resultDataType = eachFunction.getReturnType().getType().toString();
      String arguments = FunctionDesc.dataTypesToStr(eachFunction.getParameterTypesList());
      String functionType = eachFunction.getType().toString();
      String description = eachFunction.getDescription();

      int index = 0;
      printLeft(" " + name, columnWidths[index++]);
      context.getOutput().print("|");
      printLeft(" " + resultDataType, columnWidths[index++]);
      context.getOutput().print("|");
      printLeft(" " + arguments, columnWidths[index++]);
      context.getOutput().print("|");
      printLeft(" " + description, columnWidths[index++]);
      context.getOutput().print("|");
      printLeft(" " + functionType, columnWidths[index++]);

      println();
    }

    println();
    context.getOutput().println("(" + functions.size() + ") rows");
    println();

    if (printDetail && !functions.isEmpty()) {
      Map<String, CatalogProtos.FunctionDescProto> functionMap =
          new HashMap<String, CatalogProtos.FunctionDescProto>();

      for (CatalogProtos.FunctionDescProto eachFunction: functions) {
        if (!functionMap.containsKey(eachFunction.getDescription())) {
          functionMap.put(eachFunction.getDescription(), eachFunction);
        }
      }

      for (CatalogProtos.FunctionDescProto eachFunction: functionMap.values()) {
        String signature = eachFunction.getReturnType().getType() + " " +
            CatalogUtil.getCanonicalSignature(eachFunction.getSignature(), eachFunction.getParameterTypesList());
        String fullDescription = eachFunction.getDescription();
        if(eachFunction.getDetail() != null && !eachFunction.getDetail().isEmpty()) {
          fullDescription += "\n" + eachFunction.getDetail();
        }

        context.getOutput().println("Function:    " + signature);
        context.getOutput().println("Description: " + fullDescription);
        context.getOutput().println("Example:\n" + eachFunction.getExample());
        println();
      }
    }
  }

  @Override
  public String getUsage() {
    return "[function_name]";
  }

  @Override
  public String getDescription() {
    return "show function description";
  }


}
