/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBeanException;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreAdaptor;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsSlots;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import org.junit.BeforeClass;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for JGroupsSlots with WAL enabled, fsync disabled (buffered writes).
 *
 * <p>Configuration:
 * <ul>
 *   <li>WAL enabled: true</li>
 *   <li>WAL fsync writes: false (buffered, crash recovery only)</li>
 *   <li>WAL fsync deletes: false</li>
 * </ul>
 *
 * <p>This provides crash recovery but NOT power failure safety.
 */
@State(Scope.Benchmark)
public class JGroupsSlotsBenchmark_WAL_NoFsync extends JTAStoreBase {
    static final int THREADS = 240;
    static final String BM_CLASS_NAME = JGroupsSlotsBenchmark_WAL_NoFsync.class.getSimpleName();

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BM_CLASS_NAME + ".testJGroupsSlotsStore")
                .timeUnit(TimeUnit.SECONDS)
                .threads(THREADS)
                .forks(FORKS)
                .mode(Mode.Throughput)
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(ITERATIONS)
                .measurementTime(TimeValue.seconds(TIME_PER_ITER))
                .param("networkDelay", "0")
                .shouldDoGC(true)
                .addProfiler(JavaFlightRecorderProfiler.class)
                .jvmArgs("-Djmh.executor=FJP")
                .build();

        new Runner(opt).run();
    }

    @Setup(Level.Trial)
    @BeforeClass
    public static void setup() throws CoreEnvironmentBeanException {
        JTAStoreBase.setup(SlotStoreAdaptor.class.getName());
        JGroupsStoreEnvironmentBean configBean = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        int threadCount = getThreadCountFromProperties(THREADS);

        // JGroups configuration
        configBean.setJGroupsConfigFileName("jgroups.xml");
        configBean.setNodeAddress("benchmark-node-wal-nofsync");
        configBean.setGroupName("jgroups-slots-wal-nofsync-" + System.currentTimeMillis());
        configBean.setReplicationCount((short) -1);

        // WAL configuration - BUFFERED MODE
        configBean.setWalEnabled(true);       // Enable WAL
        configBean.setWalSyncWrites(false);   // No fsync (buffered, crash recovery only)
        configBean.setWalSyncDeletes(false);

        // Artemis Journal batching configuration (matches HQStoreBenchmark)
        configBean.setWalBufferFlushesPerSecond(300);  // Same as HQStore
        // walBufferSize uses default (490KB) - same as HQStore default

        // L2 cache configuration
        configBean.setCachingTime(0L);

        // Set the slot size
        configBean.setNumberOfSlots(roundUp(256, threadCount));
        configBean.setBackingSlotsClassName(JGroupsSlots.class.getName());

        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @TearDown(Level.Trial)
    public static void tearDown() {
        JGroupsStoreEnvironmentBean configBean = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @Benchmark
    public void testJGroupsSlotsStore(Blackhole bh) throws HeuristicRollbackException, SystemException, HeuristicMixedException, NotSupportedException, RollbackException {
        bh.consume(super.jtaTest());
    }
}
