/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBeanException;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreAdaptor;
import com.arjuna.ats.internal.arjuna.objectstore.slot.jgroups.JGroupsRaftSlots;
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
 * JMH Benchmark for JGroupsRaftSlots store (Raft consensus with persistent WAL).
 *
 * <p>Performance characteristics (single-node for benchmark):
 * <ul>
 *   <li>With fsync: ~10-20ms write latency, 100-200 ops/sec (maximum durability)</li>
 *   <li>Without fsync: ~1-2ms write latency, 1000+ ops/sec (buffered writes)</li>
 *   <li>Read latency: ~0.1ms (local reads from state machine)</li>
 * </ul>
 *
 * <p><b>Note</b>: This benchmark runs a single-node Raft cluster for performance testing.
 * In production, Raft requires 3+ nodes for fault tolerance.
 *
 * <p>Run from IDE: Run main() method
 * <p>Run from command line: See performance/README.md
 */
@State(Scope.Benchmark)
public class JGroupsRaftSlotsBenchmark extends JTAStoreBase {
    static final int THREADS = 240;
    static final String BM_CLASS_NAME = JGroupsRaftSlotsBenchmark.class.getSimpleName();

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    public static void main(String[] args) throws RunnerException {
        // Sometimes it is useful to run the benchmark directly from an IDE:
        Options opt = new OptionsBuilder()
                .include(BM_CLASS_NAME + ".testJGroupsRaftSlotsStore")
                .timeUnit(TimeUnit.SECONDS)
                .threads(THREADS)
                .forks(FORKS)
                .mode(Mode.Throughput)
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(ITERATIONS)
                .measurementTime(TimeValue.seconds(TIME_PER_ITER))
                .shouldDoGC(true)
                // use JFR as the profiler, the recording will appear in the User working directory with the
                // name "<package name>-xxx/profile.jfr", which you can change to "wherever" using
                // addProfiler(JavaFlightRecorderProfiler.class, "dir=wherever"). Java Flight Recorder data files
                // can be viewed with the jmc graphical tool or with the jfr command line tool which is in the java
                // bin directory
                .addProfiler(JavaFlightRecorderProfiler.class)
                .jvmArgs("-Djmh.executor=FJP") // ForkJoinPool
                // to debug the forks use "-agentlib:jdwp=transport=dt_socket,address=5005,server=y,suspend=y"
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
        configBean.setJGroupsConfigFileName("jgroups-raft.xml");
        configBean.setNodeAddress("raft-benchmark-node");
        configBean.setGroupName("jgroups-raft-benchmark-" + System.currentTimeMillis());

        // Note: Using default key generator (Uid-based) for single-node benchmark

        // Raft configuration (single-node cluster for benchmark)
        configBean.setRaftEnabled(true);
        configBean.setRaftMembers("raft-benchmark-node"); // Single node
        configBean.setRaftLogFsync(false);  // Set to true to benchmark with fsync (slower but durable)
        configBean.setRaftTimeout(5000);
        configBean.setRaftElectionMinInterval(150);
        configBean.setRaftElectionMaxInterval(300);
        configBean.setRaftHeartbeatInterval(50);

        // Set the slot size, making sure it's a sensible multiple
        configBean.setNumberOfSlots(roundUp(256, threadCount));
        configBean.setBackingSlotsClassName(JGroupsRaftSlots.class.getName());

        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @TearDown(Level.Trial)
    public static void tearDown() {
        StoreManager.shutdown();
        JGroupsStoreEnvironmentBean configBean = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @Benchmark
    public void testJGroupsRaftSlotsStore(Blackhole bh) throws HeuristicRollbackException, SystemException, HeuristicMixedException, NotSupportedException, RollbackException {
        bh.consume(super.jtaTest());
    }
}
