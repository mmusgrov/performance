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
 * JMH Benchmark for JGroupsSlots store (ReplCache-based with optional WAL).
 *
 * <p>Performance characteristics:
 * <ul>
 *   <li>Without WAL: ~1-2ms write latency (in-memory cache only)</li>
 *   <li>With WAL + fsync: ~10-20ms write latency (disk persistence)</li>
 *   <li>With WAL no fsync: ~3-5ms write latency (buffered writes)</li>
 * </ul>
 *
 * <p>Run from IDE: Run main() method
 * <p>Run from command line: See performance/README.md
 */
@State(Scope.Benchmark)
public class JGroupsSlotsBenchmark extends JTAStoreBase {
    static final int THREADS = 240;
    static final String BM_CLASS_NAME = JGroupsSlotsBenchmark.class.getSimpleName();

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    public static void main(String[] args) throws RunnerException {
        // Sometimes it is useful to run the benchmark directly from an IDE:
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
                .param("networkDelay", "0") // don't simulate network using MS_DELAY
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
        configBean.setJGroupsConfigFileName("jgroups.xml");
        configBean.setNodeAddress("benchmark-node");
        configBean.setGroupName("jgroups-slots-benchmark-" + System.currentTimeMillis());
        configBean.setReplicationCount((short) -1); // Full replication

        // Note: Using default key generator (Uid-based) for single-node benchmark

        // WAL configuration (disabled for maximum performance in single-node benchmark)
        configBean.setWalEnabled(false);  // Set to true to benchmark with WAL
        configBean.setWalSyncWrites(false); // Set to true for maximum durability
        configBean.setWalSyncDeletes(false);

        // L2 cache configuration (disabled for consistency)
        configBean.setCachingTime(0L); // No L2 caching

        // Set the slot size, making sure it's a sensible multiple
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
