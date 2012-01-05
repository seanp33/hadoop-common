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
package org.apache.hadoop.hdfs.server.namenode.ha;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.TestDFSClientFailover;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManagerTestUtil;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeAdapter;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.MultithreadedTestUtil.RepeatingTestThread;
import org.apache.hadoop.test.MultithreadedTestUtil.TestContext;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

import com.google.common.base.Supplier;


/**
 * Stress-test for potential bugs when replication is changing
 * on blocks during a failover.
 */
public class TestDNFencingWithReplication {
  static {
    ((Log4JLogger)FSNamesystem.auditLog).getLogger().setLevel(Level.WARN);
    ((Log4JLogger)Server.LOG).getLogger().setLevel(Level.FATAL);
    ((Log4JLogger)LogFactory.getLog(
        "org.apache.hadoop.io.retry.RetryInvocationHandler"))
        .getLogger().setLevel(Level.FATAL);
  }

  private static final int NUM_THREADS = 20;
  // How long should the test try to run for. In practice
  // it runs for ~20-30s longer than this constant due to startup/
  // shutdown time.
  private static final long RUNTIME = 35000;
  private static final int BLOCK_SIZE = 1024;
  
  private static class ReplicationToggler extends RepeatingTestThread {
    private final FileSystem fs;
    private final Path path;

    public ReplicationToggler(TestContext ctx, FileSystem fs, Path p) {
      super(ctx);
      this.fs = fs;
      this.path = p;
    }

    @Override
    public void doAnAction() throws Exception {
      fs.setReplication(path, (short)1);
      waitForReplicas(1);
      fs.setReplication(path, (short)2);
      waitForReplicas(2);
    }
    
    private void waitForReplicas(final int replicas) throws Exception {
      try {
        GenericTestUtils.waitFor(new Supplier<Boolean>() {
          @Override
          public Boolean get() {
            try {
              BlockLocation[] blocks = fs.getFileBlockLocations(path, 0, 10);
              Assert.assertEquals(1, blocks.length);
              return blocks[0].getHosts().length == replicas;
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        }, 100, 60000);
      } catch (TimeoutException te) {
        throw new IOException("Timed out waiting for " + replicas + " replicas " +
            "on path " + path);
      }
    }
    
    public String toString() {
      return "Toggler for " + path;
    }
  }
  
  @Test
  public void testFencingStress() throws Exception {
    Configuration conf = new Configuration();
    conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    conf.setInt(DFSConfigKeys.DFS_BLOCKREPORT_INTERVAL_MSEC_KEY, 1000);
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    // Increase max streams so that we re-replicate quickly.
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_REPLICATION_MAX_STREAMS_KEY, 1000);

    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .nnTopology(MiniDFSNNTopology.simpleHATopology())
      .numDataNodes(3)
      .build();
    try {
      cluster.waitActive();
      cluster.transitionToActive(0);
      
      final NameNode nn1 = cluster.getNameNode(0);
      final NameNode nn2 = cluster.getNameNode(1);
      nn2.getNamesystem().getEditLogTailer().setSleepTime(250);
      nn2.getNamesystem().getEditLogTailer().interrupt();
      
      FileSystem fs = TestDFSClientFailover.configureFailoverFs(
          cluster, conf);
      TestContext togglers = new TestContext();
      for (int i = 0; i < NUM_THREADS; i++) {
        Path p = new Path("/test-" + i);
        DFSTestUtil.createFile(fs, p, BLOCK_SIZE*10, (short)3, (long)i);
        togglers.addThread(new ReplicationToggler(togglers, fs, p));
      }
      
      // Start a separate thread which will make sure that replication
      // happens quickly by triggering deletion reports and replication
      // work calculation frequently.
      TestContext triggerCtx = new TestContext();
      triggerCtx.addThread(new RepeatingTestThread(triggerCtx) {
        
        @Override
        public void doAnAction() throws Exception {
          for (DataNode dn : cluster.getDataNodes()) {
            DataNodeAdapter.triggerDeletionReport(dn);
            DataNodeAdapter.triggerHeartbeat(dn);
          }
          for (int i = 0; i < 2; i++) {
            NameNode nn = cluster.getNameNode(i);
            BlockManagerTestUtil.computeAllPendingWork(
                nn.getNamesystem().getBlockManager());
          }
          Thread.sleep(500);
        }
      });
      
      triggerCtx.addThread(new RepeatingTestThread(triggerCtx) {
        
        @Override
        public void doAnAction() throws Exception {
          System.err.println("==============================\n" +
              "Failing over from 0->1\n" +
              "==================================");
          cluster.transitionToStandby(0);
          cluster.transitionToActive(1);
          
          Thread.sleep(5000);
          System.err.println("==============================\n" +
              "Failing over from 1->0\n" +
              "==================================");

          cluster.transitionToStandby(1);
          cluster.transitionToActive(0);
          Thread.sleep(5000);
        }
      });
      
      triggerCtx.startThreads();
      togglers.startThreads();
      
      togglers.waitFor(RUNTIME);
      togglers.stop();
      triggerCtx.stop();

      // CHeck that the files can be read without throwing
      for (int i = 0; i < NUM_THREADS; i++) {
        Path p = new Path("/test-" + i);
        DFSTestUtil.readFile(fs, p);
      }
    } finally {
      System.err.println("===========================\n\n\n\n");
      cluster.shutdown();
    }

  }
}