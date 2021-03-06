/*
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
package org.apache.cassandra.hadoop.cql3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.cassandra.hadoop.AbstractColumnFamilyInputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.hadoop.mapreduce.TaskAttemptID;

/**
 * Hadoop InputFormat allowing map/reduce against Cassandra rows within one ColumnFamily.
 *
 * At minimum, you need to set the KS and CF in your Hadoop job Configuration.  
 * The ConfigHelper class is provided to make this
 * simple:
 *   ConfigHelper.setInputColumnFamily
 *
 * You can also configure the number of rows per InputSplit with
 *   ConfigHelper.setInputSplitSize. The default split size is 64k rows.
 *   the number of CQL rows per page
 *   
 *   the number of CQL rows per page
 *   CQLConfigHelper.setInputCQLPageRowSize. The default page row size is 1000. You 
 *   should set it to "as big as possible, but no bigger." It set the LIMIT for the CQL 
 *   query, so you need set it big enough to minimize the network overhead, and also
 *   not too big to avoid out of memory issue.
 *   
 *   the column names of the select CQL query. The default is all columns
 *   CQLConfigHelper.setInputColumns
 *   
 *   the user defined the where clause
 *   CQLConfigHelper.setInputWhereClauses. The default is no user defined where clause
 */
public class CqlPagingInputFormat extends AbstractColumnFamilyInputFormat<Map<String, ByteBuffer>, Map<String, ByteBuffer>>
{
    public RecordReader<Map<String, ByteBuffer>, Map<String, ByteBuffer>> getRecordReader(InputSplit split, JobConf jobConf, final Reporter reporter)
            throws IOException
    {
        TaskAttemptContext tac = new TaskAttemptContextImpl(jobConf, TaskAttemptID.forName(jobConf.get(MAPRED_TASK_ID)))
        {
            @Override
            public void progress()
            {
                reporter.progress();
            }
        };

        CqlPagingRecordReader recordReader = new CqlPagingRecordReader();
        recordReader.initialize((org.apache.hadoop.mapreduce.InputSplit)split, tac);
        return recordReader;
    }

    @Override
    public org.apache.hadoop.mapreduce.RecordReader<Map<String, ByteBuffer>, Map<String, ByteBuffer>> createRecordReader(
            org.apache.hadoop.mapreduce.InputSplit arg0, TaskAttemptContext arg1) throws IOException,
            InterruptedException
    {
        return new CqlPagingRecordReader();
    }

}
