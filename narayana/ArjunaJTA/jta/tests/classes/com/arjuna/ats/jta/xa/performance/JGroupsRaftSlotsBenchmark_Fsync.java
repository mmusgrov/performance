/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBeanException;
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
 * JMH Benchmark for JGroupsRaftSlots with fsync enabled (disaster recovery mode).
 *
 * <p>Configuration:
 * <ul>
 *   <li>Raft enabled: true</li>
 *   <li>Raft log fsync: true (maximum durability)</li>
 * </ul>
 *
 * <p>This provides crash + power failure safety (disaster recovery).
 */
@State(Scope.Benchmark)
public class JGroupsRaftSlotsBenchmark_Fsync extends JTAStoreBase {
    static final int THREADS = 240;
    static final String BM_CLASS_NAME = JGroupsRaftSlotsBenchmark_Fsync.class.getSimpleName();

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    public static void main(String[] args) throws RunnerException {
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
        configBean.setJGroupsConfigFileName("jgroups-raft.xml");
        configBean.setNodeAddress("raft-benchmark-fsync-node");
        configBean.setGroupName("jgroups-raft-fsync-" + System.currentTimeMillis());

        // Raft configuration - DISASTER RECOVERY MODE
        configBean.setRaftEnabled(true);
        configBean.setRaftMembers("raft-benchmark-fsync-node");
        configBean.setRaftLogFsync(true);     // fsync enabled (crash + power failure safe)
        configBean.setRaftTimeout(5000);
        configBean.setRaftElectionMinInterval(150);
        configBean.setRaftElectionMaxInterval(300);
        configBean.setRaftHeartbeatInterval(50);

        // Set the slot size
        configBean.setNumberOfSlots(roundUp(256, threadCount));
        configBean.setBackingSlotsClassName(JGroupsRaftSlots.class.getName());

        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @TearDown(Level.Trial)
    public static void tearDown() {
        JGroupsStoreEnvironmentBean configBean = BeanPopulator.getDefaultInstance(JGroupsStoreEnvironmentBean.class);
        cleanStore(Paths.get(configBean.getStoreDir()).toFile());
    }

    @Benchmark
    public void testJGroupsRaftSlotsStore(Blackhole bh) throws HeuristicRollbackException, SystemException, HeuristicMixedException, NotSupportedException, RollbackException {
        bh.consume(super.jtaTest());
    }
}
