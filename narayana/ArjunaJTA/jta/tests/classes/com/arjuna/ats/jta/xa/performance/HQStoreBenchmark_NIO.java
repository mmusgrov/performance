/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBeanException;
import com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqJournalEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqObjectStoreAdaptor;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class HQStoreBenchmark_NIO extends JTAStoreBase {

    static final int THREADS = 240;
    static final String BM_CLASS_NAME = HQStoreBenchmark_NIO.class.getSimpleName();

    static final int FORKS = 1;
    static final int ITERATIONS = 5;
    static final int TIME_PER_ITER = 2;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BM_CLASS_NAME + ".testHQStore")
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
    @BeforeClass
    public static void setup() throws CoreEnvironmentBeanException {
        HornetqJournalEnvironmentBean hornetqJournalEnvironmentBean = BeanPopulator.getDefaultInstance(HornetqJournalEnvironmentBean.class);
        cleanStore(Paths.get(hornetqJournalEnvironmentBean.getStoreDir()).toFile());
        // Force NIO (disable AIO)
        hornetqJournalEnvironmentBean.setAsyncIO(false);
        hornetqJournalEnvironmentBean.setSyncDeletes(false);
        hornetqJournalEnvironmentBean.setBufferFlushesPerSecond(300);
        hornetqJournalEnvironmentBean.setMaxIO(500);
        JTAStoreBase.setup(HornetqObjectStoreAdaptor.class.getName());
    }

    @TearDown
    public static void tearDown() {
        String storeDir = BeanPopulator.getDefaultInstance(HornetqJournalEnvironmentBean.class).getStoreDir();
        cleanStore(Paths.get(storeDir).toFile());
    }

    @Benchmark
    public void testHQStore(Blackhole bh) throws HeuristicRollbackException, SystemException, HeuristicMixedException, NotSupportedException, RollbackException {
        bh.consume(super.jtaTest());
    }
}
