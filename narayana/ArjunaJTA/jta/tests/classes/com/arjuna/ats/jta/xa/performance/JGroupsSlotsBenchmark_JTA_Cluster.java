/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance;

import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreAdaptor;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.ByteArrayKey;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsSlots;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import org.jgroups.blocks.ReplCache;
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

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JTA-level benchmark with a 3-node JGroups cluster.
 *
 * <p>One node runs full JTA transactions via StoreManager (the JVM singleton
 * constraint prevents multiple TMs). Two peer ReplCache instances join the
 * same cluster as active replication targets, receiving every write. This
 * measures the JTA throughput cost of replicating to peers, directly
 * comparable to the single-node {@link JGroupsSlotsBenchmark}.
 */
@State(Scope.Benchmark)
public class JGroupsSlotsBenchmark_JTA_Cluster extends JTAStoreBase {

    static final int NUM_PEERS = 2;
    static final int THREADS = 240;
    static final String BM_CLASS_NAME = JGroupsSlotsBenchmark_JTA_Cluster.class.getSimpleName();

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    private static final List<ReplCache<ByteArrayKey, byte[]>> peerCaches = new ArrayList<>();

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BM_CLASS_NAME + ".testJGroupsJTACluster")
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
    public static void setup() throws Exception {
        String clusterName = "jta-cluster-bm-" + System.currentTimeMillis();
        int threadCount = getThreadCountFromProperties(THREADS);

        // Configure the primary JTA node
        JTAStoreBase.setup(SlotStoreAdaptor.class.getName());
        JGroupsStoreEnvironmentBean configBean = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        configBean.setJGroupsConfigFileName("jgroups.xml");
        configBean.setNodeAddress("primary");
        configBean.setGroupName(clusterName);
        configBean.setCacheName(clusterName);
        configBean.setReplicationCount((short) -1);
        configBean.setNumberOfSlots(roundUp(256, threadCount));
        configBean.setBackingSlotsClassName(JGroupsSlots.class.getName());
        configBean.setCachingTime(0L);
        cleanStore(Paths.get(configBean.getStoreDir()).toFile());

        // Start peer ReplCache nodes that join the same cluster
        for (int i = 0; i < NUM_PEERS; i++) {
            ReplCache<ByteArrayKey, byte[]> peer = new ReplCache<>("jgroups.xml", clusterName);
            peer.setCallTimeout(1500L);
            peer.setCachingTime(0L);
            peer.setMigrateData(true);
            peer.start();
            peerCaches.add(peer);
        }

        System.out.printf("JTA cluster: 1 primary + %d peers, %d threads%n",
                NUM_PEERS, threadCount);
    }

    @TearDown(Level.Trial)
    public static void tearDown() {
        StoreManager.shutdown();
        for (ReplCache<ByteArrayKey, byte[]> peer : peerCaches) {
            peer.stop();
        }
        peerCaches.clear();

        JGroupsStoreEnvironmentBean configBean = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @Benchmark
    public void testJGroupsJTACluster(Blackhole bh) throws HeuristicRollbackException, SystemException, HeuristicMixedException, NotSupportedException, RollbackException {
        bh.consume(super.jtaTest());
    }
}
