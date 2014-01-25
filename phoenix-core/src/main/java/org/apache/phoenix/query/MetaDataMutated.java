/*
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.query;

import java.sql.SQLException;
import java.util.List;

import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PMetaData;
import org.apache.phoenix.schema.PTable;


/**
 * 
 * Interface for applying schema mutations to our client-side schema cache
 *
 * @author jtaylor
 * @since 0.1
 */
public interface MetaDataMutated {
    PMetaData addTable(PTable table) throws SQLException;
    PMetaData removeTable(String tableName) throws SQLException;
    PMetaData addColumn(String tableName, List<PColumn> columns, long tableTimeStamp, long tableSeqNum, boolean isImmutableRows) throws SQLException;
    PMetaData removeColumn(String tableName, String familyName, String columnName, long tableTimeStamp, long tableSeqNum) throws SQLException;
}
