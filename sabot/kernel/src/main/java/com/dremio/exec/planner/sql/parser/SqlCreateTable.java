/*
 * Copyright (C) 2017 Dremio Corporation
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
package com.dremio.exec.planner.sql.parser;

import java.util.List;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class SqlCreateTable extends SqlCall {
  public static final SqlSpecialOperator OPERATOR = new SqlSpecialOperator("CREATE_TABLE", SqlKind.CREATE_TABLE) {
    @Override
    public SqlCall createCall(SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
      Preconditions.checkArgument(operands.length == 10, "SqlCreateTable.createCall() has to get 9 operands!");
      return new SqlCreateTable(pos, (SqlIdentifier) operands[0], (SqlNodeList) operands[1], (SqlNodeList) operands[2],
        ((SqlLiteral) operands[3]).booleanValue(), ((SqlLiteral) operands[4]).booleanValue(),
        (SqlNodeList) operands[5], (SqlLiteral) operands[6], operands[7], (SqlNodeList) operands[8], (SqlNodeList) operands[9]);
    }
  };

  private final SqlIdentifier tblName;
  private final SqlNodeList fieldList;
  private final SqlNodeList partitionColumns;
  private final boolean hashPartition;
  private final boolean roundRobinPartition;
  private final SqlNodeList sortColumns;
  private final SqlNodeList distributionColumns;
  private final SqlNodeList formatOptions;
  private final SqlLiteral singleWriter;
  private final SqlNode query;

  public SqlCreateTable(
      SqlParserPos pos,
      SqlIdentifier tblName,
      SqlNodeList fieldList,
      SqlNodeList partitionColumns,
      boolean hashPartition,
      boolean roundRobinPartition,
      SqlNodeList formatOptions,
      SqlLiteral singleWriter,
      SqlNode query,
      SqlNodeList sortFieldList,
      SqlNodeList distributionColumns) {
    super(pos);
    this.tblName = tblName;
    this.fieldList = fieldList;
    this.partitionColumns = partitionColumns;
    this.formatOptions = formatOptions;
    this.singleWriter = singleWriter;
    this.query = query;
    this.sortColumns = sortFieldList;
    this.distributionColumns = distributionColumns;
    this.hashPartition = hashPartition;
    this.roundRobinPartition = roundRobinPartition;
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    List<SqlNode> ops = Lists.newArrayList();
    ops.add(tblName);
    ops.add(fieldList);
    ops.add(partitionColumns);
    ops.add(SqlLiteral.createBoolean(hashPartition, SqlParserPos.ZERO));
    ops.add(SqlLiteral.createBoolean(roundRobinPartition, SqlParserPos.ZERO));
    ops.add(formatOptions);
    ops.add(singleWriter);
    ops.add(query);
    ops.add(sortColumns);
    ops.add(distributionColumns);
    return ops;
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    writer.keyword("CREATE");
    writer.keyword("TABLE");
    tblName.unparse(writer, leftPrec, rightPrec);
    if (fieldList.size() > 0) {
      SqlHandlerUtil.unparseSqlNodeList(writer, leftPrec, rightPrec, fieldList);
    }
    if (partitionColumns.size() > 0) {
      if (hashPartition) {
        writer.keyword("HASH");
      } else if (roundRobinPartition) {
        writer.keyword("ROUNDROBIN");
      }
      writer.keyword("PARTITION");
      writer.keyword("BY");
      SqlHandlerUtil.unparseSqlNodeList(writer, leftPrec, rightPrec, partitionColumns);
    }
    if(distributionColumns.size() > 0) {
      writer.keyword("DISTRIBUTE");
      writer.keyword("BY");
      SqlHandlerUtil.unparseSqlNodeList(writer, leftPrec, rightPrec, distributionColumns);
    }
    if(sortColumns.size() > 0) {
      writer.keyword("LOCALSORT");
      writer.keyword("BY");
      SqlHandlerUtil.unparseSqlNodeList(writer, leftPrec, rightPrec, sortColumns);
    }
    if (formatOptions.size() > 0) {
      writer.keyword("STORE");
      writer.keyword("AS");
      SqlHandlerUtil.unparseSqlNodeList(writer, leftPrec, rightPrec, formatOptions);
    }
    if (singleWriter.booleanValue()) {
      writer.keyword("WITH");
      writer.keyword("SINGLE");
      writer.keyword("WRITER");
    }
    writer.keyword("AS");
    query.unparse(writer, leftPrec, rightPrec);
  }

  public List<String> getSchemaPath() {
    if (tblName.isSimple()) {
      return ImmutableList.of();
    }

    return tblName.names.subList(0, tblName.names.size() - 1);
  }

  public String getName() {
    if (tblName.isSimple()) {
      return tblName.getSimple();
    }

    return tblName.names.get(tblName.names.size() - 1);
  }

  public List<String> getFieldNames() {
    List<String> columnNames = Lists.newArrayList();
    for(SqlNode node : fieldList.getList()) {
      columnNames.add(node.toString());
    }
    return columnNames;
  }

  public List<String> getSortColumns() {
    List<String> columnNames = Lists.newArrayList();
    for(SqlNode node : sortColumns.getList()) {
      columnNames.add(node.toString());
    }
    return columnNames;
  }

  public boolean isHashPartition() {
    return hashPartition;
  }

  public boolean isRoundRobinPartition() {
    return roundRobinPartition;
  }

  public List<String> getDistributionColumns() {
    List<String> columnNames = Lists.newArrayList();
    for(SqlNode node : distributionColumns.getList()) {
      columnNames.add(node.toString());
    }
    return columnNames;
  }

  public List<String> getPartitionColumns() {
    List<String> columnNames = Lists.newArrayList();
    for(SqlNode node : partitionColumns.getList()) {
      columnNames.add(node.toString());
    }
    return columnNames;
  }

  public SqlNodeList getFormatOptions() {
    return formatOptions;
  }

  public boolean isSingleWriter() {
    return singleWriter.booleanValue();
  }

  public SqlNode getQuery() {
    return query;
  }
}
