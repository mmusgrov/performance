/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStore;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreKey;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsSlots;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsStoreEnvironmentBean;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import org.openjdk.jmh.annotations.Param;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-node JGroupsSlots cluster benchmark.
 *
 * <p>Measures SlotStore throughput with 3 active nodes replicating via JGroups ReplCache.
 * All nodes write and remove transaction records concurrently, matching the production
 * pattern where any cluster member can run transactions.
 *
 * <p>Operates at the SlotStore level (slot allocation, serialization, replication, removal)
 * rather than the full JTA level, since StoreManager is a JVM singleton. The JTA protocol
 * overhead (begin/enlist/prepare) is constant across store types.
 *
 * <p>Uses SHARED_LOOPBACK transport so all nodes run in one JVM with the full JGroups
 * protocol stack (serialization, NAKACK2, GMS, flow control) but no network latency.
 */
@State(Scope.Benchmark)
public class JGroupsSlotsBenchmark_Cluster {

    @Param({"1", "3"})
    int numNodes;

    static final int THREADS = 240;
    static final String BM_CLASS_NAME = JGroupsSlotsBenchmark_Cluster.class.getSimpleName();
    static final String TYPE_NAME = "/StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction";
    static final byte[] PAYLOAD = new byte[512];

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    private SlotStore[] stores;
    private JGroupsStoreEnvironmentBean[] configs;
    private final AtomicInteger nodeAssigner = new AtomicInteger(0);

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BM_CLASS_NAME + ".testClusteredSlotStore")
                .timeUnit(TimeUnit.SECONDS)
                .threads(THREADS)
                .forks(FORKS)
                .mode(Mode.Throughput)
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(ITERATIONS)
                .measurementTime(TimeValue.seconds(TIME_PER_ITER))
                .shouldDoGC(true)
                .jvmArgs("-Djmh.executor=FJP")
                .build();

        new Runner(opt).run();
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String clusterName = "cluster-bm-" + System.currentTimeMillis();
        int threadCount = JTAStoreBase.getThreadCountFromProperties(THREADS);
        int numSlots = roundUp(256, threadCount);

        stores = new SlotStore[numNodes];
        configs = new JGroupsStoreEnvironmentBean[numNodes];

        for (int i = 0; i < numNodes; i++) {
            configs[i] = new JGroupsStoreEnvironmentBean();
            configs[i].setJGroupsConfigFileName("jgroups.xml");
            configs[i].setNodeAddress("node-" + i);
            configs[i].setGroupName(clusterName);
            configs[i].setCacheName(clusterName);
            configs[i].setReplicationCount((short) -1);
            configs[i].setNumberOfSlots(numSlots);
            configs[i].setStoreDir("target/cluster-node-" + i);
            configs[i].setBackingSlotsClassName(JGroupsSlots.class.getName());
            configs[i].setCachingTime(0L);

            cleanStore(Paths.get(configs[i].getStoreDir()).toFile());
            stores[i] = new SlotStore(configs[i]);
        }

        // Wait for all nodes to see the full cluster
        long deadline = System.currentTimeMillis() + 10_000;
        boolean formed = false;
        while (System.currentTimeMillis() < deadline) {
            boolean allReady = true;
            for (int i = 0; i < numNodes; i++) {
                if (configs[i].getCache().getClusterSize() < numNodes) {
                    allReady = false;
                    break;
                }
            }
            if (allReady) {
                formed = true;
                break;
            }
            Thread.sleep(50);
        }
        if (!formed) {
            throw new IllegalStateException("Cluster did not form with " + numNodes + " nodes within 10s");
        }

        System.out.printf("Cluster formed: %d nodes, %d slots/node, %d threads%n",
                numNodes, numSlots, threadCount);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        for (int i = 0; i < stores.length; i++) {
            if (stores[i] != null) {
                try {
                    stores[i].stop();
                } catch (Exception e) {
                    System.err.printf("Warn: failed to stop store %d: %s%n", i, e.getMessage());
                }
            }
            if (configs[i] != null) {
                try {
                    configs[i].getCache().stop();
                } catch (Exception e) {
                    // cache may already be stopped
                }
                cleanStore(Paths.get(configs[i].getStoreDir()).toFile());
            }
        }
    }

    @Benchmark
    public boolean testClusteredSlotStore(Blackhole bh, ThreadState ts) throws IOException {
        Uid uid = new Uid();
        SlotStoreKey key = new SlotStoreKey(uid, TYPE_NAME, StateStatus.OS_COMMITTED);
        OutputObjectState state = new OutputObjectState(uid, TYPE_NAME);
        state.packBytes(PAYLOAD);

        boolean written = ts.store.write(key, state);
        if (written) {
            ts.store.remove(key);
        }
        return written;
    }

    @State(Scope.Thread)
    public static class ThreadState {
        SlotStore store;

        @Setup(Level.Trial)
        public void setup(JGroupsSlotsBenchmark_Cluster cluster) {
            int idx = cluster.nodeAssigner.getAndIncrement() % cluster.numNodes;
            store = cluster.stores[idx];
        }
    }

    private static int roundUp(int increment, int value) {
        int res = value % increment;
        if (res == 0) {
            return value;
        } else {
            return value + increment - res;
        }
    }

    private static void cleanStore(File storeDir) {
        try {
            if (!purgeFiles(storeDir)) {
                System.err.printf("problem removing slot store file storage (%s)%n", storeDir);
            }
        } catch (Exception e) {
            System.err.printf("Warn: problem cleaning the object store (%s): %s%n", storeDir, e.getMessage());
        }
    }

    private static boolean purgeFiles(File storeDir) {
        File[] files = storeDir.listFiles();
        if (files != null) {
            for (File file : files) {
                purgeFiles(file);
            }
        }
        return storeDir.delete();
    }
}
