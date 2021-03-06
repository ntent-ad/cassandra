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
package org.apache.cassandra.db.compaction;

import java.nio.ByteBuffer;
import java.util.*;

import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.metrics.RestorableMeter;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;

import static org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy.getBuckets;
import static org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy.mostInterestingBucket;
import static org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy.trimToThresholdWithHotness;
import static org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy.filterColdSSTables;
import static org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy.validateOptions;

import static org.junit.Assert.*;

public class SizeTieredCompactionStrategyTest extends SchemaLoader
{

    @Test
    public void testOptionsValidation() throws ConfigurationException
    {
        Map<String, String> options = new HashMap<>();
        options.put(SizeTieredCompactionStrategyOptions.COLD_READS_TO_OMIT_KEY, "0.35");
        options.put(SizeTieredCompactionStrategyOptions.BUCKET_LOW_KEY, "0.5");
        options.put(SizeTieredCompactionStrategyOptions.BUCKET_HIGH_KEY, "1.5");
        options.put(SizeTieredCompactionStrategyOptions.MIN_SSTABLE_SIZE_KEY, "10000");
        Map<String, String> unvalidated = validateOptions(options);
        assertTrue(unvalidated.isEmpty());

        try
        {
            options.put(SizeTieredCompactionStrategyOptions.COLD_READS_TO_OMIT_KEY, "-0.5");
            validateOptions(options);
            fail(String.format("Negative %s should be rejected", SizeTieredCompactionStrategyOptions.COLD_READS_TO_OMIT_KEY));
        }
        catch (ConfigurationException e) {}

        try
        {
            options.put(SizeTieredCompactionStrategyOptions.COLD_READS_TO_OMIT_KEY, "10.0");
            validateOptions(options);
            fail(String.format("%s > 1.0 should be rejected", SizeTieredCompactionStrategyOptions.COLD_READS_TO_OMIT_KEY));
        }
        catch (ConfigurationException e)
        {
            options.put(SizeTieredCompactionStrategyOptions.COLD_READS_TO_OMIT_KEY, "0.25");
        }

        try
        {
            options.put(SizeTieredCompactionStrategyOptions.BUCKET_LOW_KEY, "1000.0");
            validateOptions(options);
            fail("bucket_low greater than bucket_high should be rejected");
        }
        catch (ConfigurationException e)
        {
            options.put(SizeTieredCompactionStrategyOptions.BUCKET_LOW_KEY, "0.5");
        }

        options.put("bad_option", "1.0");
        unvalidated = validateOptions(options);
        assertTrue(unvalidated.containsKey("bad_option"));
    }

    @Test
    public void testGetBuckets()
    {
        List<Pair<String, Long>> pairs = new ArrayList<Pair<String, Long>>();
        String[] strings = { "a", "bbbb", "cccccccc", "cccccccc", "bbbb", "a" };
        for (String st : strings)
        {
            Pair<String, Long> pair = Pair.create(st, new Long(st.length()));
            pairs.add(pair);
        }

        List<List<String>> buckets = getBuckets(pairs, 1.5, 0.5, 2);
        assertEquals(3, buckets.size());

        for (List<String> bucket : buckets)
        {
            assertEquals(2, bucket.size());
            assertEquals(bucket.get(0).length(), bucket.get(1).length());
            assertEquals(bucket.get(0).charAt(0), bucket.get(1).charAt(0));
        }

        pairs.clear();
        buckets.clear();

        String[] strings2 = { "aaa", "bbbbbbbb", "aaa", "bbbbbbbb", "bbbbbbbb", "aaa" };
        for (String st : strings2)
        {
            Pair<String, Long> pair = Pair.create(st, new Long(st.length()));
            pairs.add(pair);
        }

        buckets = getBuckets(pairs, 1.5, 0.5, 2);
        assertEquals(2, buckets.size());

        for (List<String> bucket : buckets)
        {
            assertEquals(3, bucket.size());
            assertEquals(bucket.get(0).charAt(0), bucket.get(1).charAt(0));
            assertEquals(bucket.get(1).charAt(0), bucket.get(2).charAt(0));
        }

        // Test the "min" functionality
        pairs.clear();
        buckets.clear();

        String[] strings3 = { "aaa", "bbbbbbbb", "aaa", "bbbbbbbb", "bbbbbbbb", "aaa" };
        for (String st : strings3)
        {
            Pair<String, Long> pair = Pair.create(st, new Long(st.length()));
            pairs.add(pair);
        }

        buckets = getBuckets(pairs, 1.5, 0.5, 10);
        assertEquals(1, buckets.size());
    }

    @Test
    public void testPrepBucket() throws Exception
    {
        String ksname = "Keyspace1";
        String cfname = "Standard1";
        Keyspace keyspace = Keyspace.open(ksname);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(cfname);
        cfs.truncateBlocking();
        cfs.disableAutoCompaction();

        ByteBuffer value = ByteBuffer.wrap(new byte[100]);

        // create 3 sstables
        int numSSTables = 3;
        for (int r = 0; r < numSSTables; r++)
        {
            DecoratedKey key = Util.dk(String.valueOf(r));
            RowMutation rm = new RowMutation(ksname, key.key);
            rm.add(cfname, ByteBufferUtil.bytes("column"), value, 0);
            rm.apply();
            cfs.forceBlockingFlush();
        }
        cfs.forceBlockingFlush();

        List<SSTableReader> sstrs = new ArrayList<>(cfs.getSSTables());
        Pair<List<SSTableReader>, Double> bucket;

        List<SSTableReader> interestingBucket = mostInterestingBucket(Collections.singletonList(sstrs.subList(0, 2)), 4, 32);
        assertTrue("nothing should be returned when all buckets are below the min threshold", interestingBucket.isEmpty());

        sstrs.get(0).readMeter = new RestorableMeter(100.0, 100.0);
        sstrs.get(1).readMeter = new RestorableMeter(200.0, 200.0);
        sstrs.get(2).readMeter = new RestorableMeter(300.0, 300.0);

        long estimatedKeys = sstrs.get(0).estimatedKeys();

        // if we have more than the max threshold, the coldest should be dropped
        bucket = trimToThresholdWithHotness(sstrs, 2);
        assertEquals("one bucket should have been dropped", 2, bucket.left.size());
        double expectedBucketHotness = (200.0 + 300.0) / estimatedKeys;
        assertEquals(String.format("bucket hotness (%f) should be close to %f", bucket.right, expectedBucketHotness),
                     expectedBucketHotness, bucket.right, 1.0);
    }

    @Test
    public void testFilterColdSSTables() throws Exception
    {
        String ksname = "Keyspace1";
        String cfname = "Standard1";
        Keyspace keyspace = Keyspace.open(ksname);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(cfname);
        cfs.truncateBlocking();
        cfs.disableAutoCompaction();

        ByteBuffer value = ByteBuffer.wrap(new byte[100]);

        // create 10 sstables
        int numSSTables = 10;
        for (int r = 0; r < numSSTables; r++)
        {
            DecoratedKey key = Util.dk(String.valueOf(r));
            RowMutation rm = new RowMutation(ksname, key.key);
            rm.add(cfname, ByteBufferUtil.bytes("column"), value, 0);
            rm.apply();
            cfs.forceBlockingFlush();
        }
        cfs.forceBlockingFlush();

        List<SSTableReader> filtered;
        List<SSTableReader> sstrs = new ArrayList<>(cfs.getSSTables());

        for (SSTableReader sstr : sstrs)
            sstr.readMeter = null;
        filtered = filterColdSSTables(sstrs, 0.05);
        assertEquals("when there are no read meters, no sstables should be filtered", sstrs.size(), filtered.size());

        for (SSTableReader sstr : sstrs)
            sstr.readMeter = new RestorableMeter(0.0, 0.0);
        filtered = filterColdSSTables(sstrs, 0.05);
        assertEquals("when all read meters are zero, no sstables should be filtered", sstrs.size(), filtered.size());

        // leave all read rates at 0 besides one
        sstrs.get(0).readMeter = new RestorableMeter(1000.0, 1000.0);
        filtered = filterColdSSTables(sstrs, 0.05);
        assertEquals("there should only be one hot sstable", 1, filtered.size());
        assertEquals(1000.0, filtered.get(0).readMeter.twoHourRate(), 0.5);

        // the total read rate is 100, and we'll set a threshold of 2.5%, so two of the sstables with read
        // rate 1.0 should be ignored, but not the third
        for (SSTableReader sstr : sstrs)
            sstr.readMeter = new RestorableMeter(0.0, 0.0);
        sstrs.get(0).readMeter = new RestorableMeter(97.0, 97.0);
        sstrs.get(1).readMeter = new RestorableMeter(1.0, 1.0);
        sstrs.get(2).readMeter = new RestorableMeter(1.0, 1.0);
        sstrs.get(3).readMeter = new RestorableMeter(1.0, 1.0);

        filtered = filterColdSSTables(sstrs, 0.025);
        assertEquals(2, filtered.size());
        assertEquals(98.0, filtered.get(0).readMeter.twoHourRate() + filtered.get(1).readMeter.twoHourRate(), 0.5);

        // make sure a threshold of 0.0 doesn't result in any sstables being filtered
        for (SSTableReader sstr : sstrs)
            sstr.readMeter = new RestorableMeter(1.0, 1.0);
        filtered = filterColdSSTables(sstrs, 0.0);
        assertEquals(sstrs.size(), filtered.size());

        // just for fun, set a threshold where all sstables are considered cold
        for (SSTableReader sstr : sstrs)
            sstr.readMeter = new RestorableMeter(1.0, 1.0);
        filtered = filterColdSSTables(sstrs, 1.0);
        assertTrue(filtered.isEmpty());
    }
}