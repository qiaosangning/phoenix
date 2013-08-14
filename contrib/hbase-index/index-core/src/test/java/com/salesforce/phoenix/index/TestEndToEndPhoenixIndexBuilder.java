/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdge;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.salesforce.hbase.index.IndexUtil;
import com.salesforce.hbase.index.Indexer;
import com.salesforce.hbase.index.builder.covered.ColumnReference;
import com.salesforce.hbase.index.builder.covered.ColumnTracker;
import com.salesforce.hbase.index.builder.covered.IndexCodec;
import com.salesforce.hbase.index.builder.covered.IndexUpdate;
import com.salesforce.hbase.index.builder.covered.TableState;
import com.salesforce.hbase.index.builder.covered.scanner.Scanner;

/**
 * End-to-End test of just the {@link PhoenixIndexBuilder}, but with a simple {@link IndexCodec} and
 * BatchCache implementation.
 */
public class TestEndToEndPhoenixIndexBuilder {

  public class TestState {

    private HTable table;
    private long ts;
    private VerifyingIndexCodec codec;

    /**
     * @param primary
     * @param codec
     * @param ts
     */
    public TestState(HTable primary, VerifyingIndexCodec codec, long ts) {
      this.table = primary;
      this.ts = ts;
      this.codec = codec;
    }

  }

  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();

  private static final byte[] row = Bytes.toBytes("row");
  private static final byte[] family = Bytes.toBytes("FAM");
  private static final byte[] qual = Bytes.toBytes("qual");
  private static final HColumnDescriptor FAM1 = new HColumnDescriptor(family);

  @BeforeClass
  public static void setupCluster() throws Exception {
    Configuration conf = UTIL.getConfiguration();
    // disable version checking, so we can test against whatever version of HBase happens to be
    // installed (right now, its generally going to be SNAPSHOT versions).
    conf.setBoolean(Indexer.CHECK_VERSION_CONF_KEY, false);
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void shutdownCluster() throws Exception {
    UTIL.shutdownMiniCluster();
  }
    
  private interface TableStateVerifier {

    /**
     * Verify that the state of the table is correct. Should fail the unit test if it isn't as
     * expected.
     * @param state
     */
    public void verify(TableState state);

  }

  /**
   * {@link TableStateVerifier} that ensures the kvs returned from the table match the passed
   * {@link KeyValue}s when querying on the given columns.
   */
  private class ListMatchingVerifier implements TableStateVerifier {

    private List<KeyValue> expectedKvs;
    private ColumnReference[] columns;
    private String msg;

    public ListMatchingVerifier(String msg, List<KeyValue> kvs, ColumnReference... columns) {
      this.expectedKvs = kvs;
      this.columns = columns;
      this.msg = msg;
    }

    @Override
    public void verify(TableState state) {
      try {
        Scanner kvs = state.getNonIndexedColumnsTableState(Arrays.asList(columns));

        int count = 0;
        KeyValue kv;
        while ((kv = kvs.next()) != null) {
          assertEquals(msg + ": Unexpected kv in table state!", expectedKvs.get(count++), kv);
        }

        assertEquals(msg + ": Didn't find enough kvs in table state!", expectedKvs.size(), count);
      } catch (IOException e) {
        fail(msg + ": Got an exception while reading local table state! " + e.getMessage());
      }
    }
  }

  private class VerifyingIndexCodec extends CoveredIndexCodecForTesting {

    private Queue<TableStateVerifier> verifiers = new ArrayDeque<TableStateVerifier>();

    @Override
    public Iterable<Pair<Delete, byte[]>> getIndexDeletes(TableState state) {
      verify(state);
      return super.getIndexDeletes(state);
    }

    @Override
    public Iterable<IndexUpdate> getIndexUpserts(TableState state) {
      verify(state);
      return super.getIndexUpserts(state);
    }

    private void verify(TableState state) {
      TableStateVerifier verifier = verifiers.poll();
      if (verifier == null) return;
      verifier.verify(state);
    }
  }
  
  /**
   * Test that we see the expected values in a {@link TableState} when doing single puts against a
   * region.
   * @throws Exception on failure
   */
  @Test
  public void testExpectedResultsInTableStateForSinglePut() throws Exception {
    TestState state = setupTest("testExpectedResultsInTableState");

    //just do a simple Put to start with
    long ts = state.ts;
    Put p = new Put(row, ts);
    p.add(family, qual, Bytes.toBytes("v1"));
    
    // get all the underlying kvs for the put
    final List<KeyValue> expectedKvs = new ArrayList<KeyValue>();
    final List<KeyValue> allKvs = new ArrayList<KeyValue>();
    allKvs.addAll(p.getFamilyMap().get(family));

    // setup the verifier for the data we expect to write
    // first call shouldn't have anything in the table
    final ColumnReference familyRef =
        new ColumnReference(TestEndToEndPhoenixIndexBuilder.family, ColumnReference.ALL_QUALIFIERS);

    VerifyingIndexCodec codec = state.codec;
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 1", expectedKvs, familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 1", allKvs, familyRef));

    // do the actual put (no indexing will actually be done)
    HTable primary = state.table;
    primary.put(p);
    primary.flushCommits();

    // now we do another put to the same row. We should see just the old row state, followed by the
    // new + old
    p = new Put(row, ts + 1);
    p.add(family, qual, Bytes.toBytes("v2"));
    expectedKvs.addAll(allKvs);
    // add them first b/c the ts is newer
    allKvs.addAll(0, p.get(family, qual));
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 2", expectedKvs, familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 2", allKvs, familyRef));
    
    // do the actual put
    primary.put(p);
    primary.flushCommits();

    // cleanup after ourselves
    cleanup(state);
  }

  /**
   * Similar to {@link #testExpectedResultsInTableStateForSinglePut()}, but against batches of puts.
   * This case is a little more complicated as we need to use the row cache (based on the response
   * from the BatchCache) and keep updating the row cache with each part of the batch.
   * <p>
   * This test actually verifies the rather odd behavior documented in the
   * {@link PhoenixIndexBuilder} where two updates to the same row in the same batch are completely
   * independent in the state of the row they see.
   * @throws Exception on failure
   */
  @Test
  public void testExpectedResultsInTableStateForBatchPuts() throws Exception {
    String tableName = "testExpectedResultsInTableStateForBatchPuts";
    TestState state = setupTest(tableName);
    long ts = state.ts;
    // build up a list of puts to make, all on the same row
    Put p1 = new Put(row, ts);
    p1.add(family, qual, Bytes.toBytes("v1"));
    Put p2 = new Put(row, ts + 1);
    p2.add(family, qual, Bytes.toBytes("v2"));

    // setup all the verifiers we need. This is just the same as above, but will be called twice
    // since we need to iterate the batch.

    // get all the underlying kvs for the put
    final List<KeyValue> expectedKvs = new ArrayList<KeyValue>();
    final List<KeyValue> allKvs = new ArrayList<KeyValue>(p1.getFamilyMap().get(family));

    // setup the verifier for the data we expect to write
    // first call shouldn't have anything in the table
    final ColumnReference familyRef =
        new ColumnReference(TestEndToEndPhoenixIndexBuilder.family, ColumnReference.ALL_QUALIFIERS);
    VerifyingIndexCodec codec = state.codec;
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 1", expectedKvs, familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 1", allKvs, familyRef));

    // second set is completely independent of the first set
    List<KeyValue> expectedKvs2 = new ArrayList<KeyValue>();
    List<KeyValue> allKvs2 = new ArrayList<KeyValue>(p2.get(family, qual));
    codec.verifiers.add(new ListMatchingVerifier("cleanup state 2", expectedKvs2, familyRef));
    codec.verifiers.add(new ListMatchingVerifier("put state 2", allKvs2, familyRef));

    // do the actual put (no indexing will actually be done)
    HTable primary = state.table;
    primary.put(Arrays.asList(p1, p2));
    primary.flushCommits();

    // cleanup after ourselves
    cleanup(state);
  }

  /**
   * One of the more tricky problems is how to manage updating the index when a put is made 'back in
   * time' from the current state of the row in the primary table. In this case, we need to issue a
   * Put for the update and then immediately also issue a delete for that Put at the next newest
   * timestamp of the indexed column.
   * @throws Exception on failure.
   */
  @Test
  public void testProperlyCoversIndexEntriesWhenPuttingBackInTime() throws Exception {
    String tableName = "testProperlyCoversIndexEntriesWhenPuttingBackInTime";
    TestState state = setupTest(tableName);
    
    //setup the index table
    byte[] indexTableName = Bytes.toBytes("indexTable_coverBackInTimeEntries");
    HTableDescriptor desc = new HTableDescriptor(indexTableName);
    desc.addFamily(new HColumnDescriptor(family));
    UTIL.getHBaseAdmin().createTable(desc);
    HTable index = new HTable(UTIL.getConfiguration(), indexTableName);
    
    long ts = state.ts;

    // make a put at the current timestamp
    byte[] value = Bytes.toBytes("v1");
    Put p = new Put(row, ts);
    p.add(family, qual, value);

    Put i = new Put(value, ts);
    i.add(family, qual, row);

    // setup the index entry we need to make. It has the exact same information as the primary
    // table, just for simplicity.
    ColumnReference familyRef = new ColumnReference(family, ColumnReference.ALL_QUALIFIERS);
    ColumnTracker tracker = new ColumnTracker(Arrays.asList(familyRef));
    IndexUpdate update = IndexUpdate.createIndexUpdateForTesting(tracker, indexTableName, i);
    state.codec.addIndexUpserts(update);

    // do the actual put
    state.table.put(p);
    state.table.flushCommits();

    // make sure index state matches
    Result r = index.get(new Get(value));
    assertEquals("Index table has unexpected number of entries!", 1, r.list().size());
    assertEquals("Got unexpected index entry!", i.get(family, qual).get(0), r.list().get(0));

    // make a Put 'back in time' from the current timestamp
    value = Bytes.toBytes("v0");
    long pastTime = ts - 2;
    p = new Put(row, pastTime);
    p.add(family, qual, value);

    i = new Put(value, pastTime);
    i.add(family, qual, row);

    // this time we need to update the tracker so it has the timestamp of the previous row
    tracker.setTs(ts);
    update = IndexUpdate.createIndexUpdateForTesting(tracker, indexTableName, i);
    // add the update to the codec
    state.codec.clear();
    state.codec.addIndexUpserts(update);

    // make the actual put
    state.table.put(p);
    state.table.flushCommits();

    // check the index table - we should see the row at ts -2, but not at ts since it should be
    // covered at a delete
    Scan s = new Scan(value, value);
    s.setTimeRange(pastTime - 1, ts - 1);
    r = index.getScanner(s).next();
    assertEquals("Index table has unexpected number of entries!", 1, r.list().size());
    assertEquals("Got unexpected index entry!", i.get(family, qual).get(0), r.list().get(0));

    // if we check at the future time, we shouldn't see the update at all
    s.setTimeStamp(ts);
    r = index.getScanner(s).next();
    assertNull("Index has entry that should have been covered!", r);

    //cleanup after ourselves
    cleanup(state);
    index.close();
    UTIL.deleteTable(indexTableName);
  }

  /**
   * @param tableName name of the table to create for the test
   * @return the supporting state for the test
   */
  private TestState setupTest(String tableName) throws IOException {
    byte[] tableNameBytes = Bytes.toBytes(tableName);
    HTableDescriptor desc = new HTableDescriptor(tableNameBytes);
    desc.addFamily(FAM1);
    // add the necessary simple options to create the builder
    Map<String, String> indexerOpts = new HashMap<String, String>();
    // just need to set the codec - we are going to set it later, but we need something here or the
    // initializer blows up.
    indexerOpts.put(PhoenixIndexBuilder.CODEC_CLASS_NAME_KEY,
      CoveredIndexCodecForTesting.class.getName());
    IndexUtil.enableIndexing(desc, PhoenixIndexBuilder.class, indexerOpts);

    // create the table
    HBaseAdmin admin = UTIL.getHBaseAdmin();
    admin.createTable(desc);
    HTable primary = new HTable(UTIL.getConfiguration(), tableNameBytes);

    // overwrite the codec so we can verify the current state
    HRegion region = UTIL.getMiniHBaseCluster().getRegions(tableNameBytes).get(0);
    Indexer indexer =
        (Indexer) region.getCoprocessorHost().findCoprocessor(Indexer.class.getName());
    PhoenixIndexBuilder builder = (PhoenixIndexBuilder) indexer.getBuilderForTesting();
    VerifyingIndexCodec codec = new VerifyingIndexCodec();
    builder.setIndexCodecForTesting(codec);

    // setup the Puts we want to write
    final long ts = System.currentTimeMillis();
    EnvironmentEdge edge = new EnvironmentEdge() {

      @Override
      public long currentTimeMillis() {
        return ts;
      }
    };
    EnvironmentEdgeManager.injectEdge(edge);

    return new TestState(primary, codec, ts);
  }

  /**
   * Cleanup the test based on the passed state.
   * @param state
   */
  private void cleanup(TestState state) throws IOException {
    EnvironmentEdgeManager.reset();
    state.table.close();
    UTIL.deleteTable(state.table.getTableName());
  }
}