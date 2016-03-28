/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 *
 * (C) 2005-2006,
 * @author JBoss Inc.
 */
/*
 * Copyright (C) 1998, 1999, 2000,
 *
 * Hewlett-Packard Arjuna Labs,
 * Newcastle upon Tyne,
 * Tyne and Wear,
 * UK.
 *
 * $Id: Performance1.java 2342 2006-03-30 13:06:17Z  $
 */

package com.hp.mwtests.ts.arjuna.performance;

import com.arjuna.ats.arjuna.common.CoordinatorEnvironmentBean;
import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.TwoPhaseVolatileStore;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import com.hp.mwtests.ts.arjuna.JMHConfigCore;

import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;

import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.CommandLineOptionException;


public class Performance2 extends PerformanceBase {
//    @Setup(Level.Trial)
//    @BeforeClass
    static void setup() {
        try {
            BeanPopulator.getDefaultInstance(CoordinatorEnvironmentBean.class).setCommitOnePhase(false);
            BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).setObjectStoreType(TwoPhaseVolatileStore.class.getName());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static {
        setup();
    }

    public static void main(String[] args) throws RunnerException, CommandLineOptionException {
        JMHConfigCore.runJTABenchmark(Performance2.class.getSimpleName(), args);
    }

    @Benchmark
    public boolean onePhase(BenchmarkState benchmarkState) {
        return super.onePhase(benchmarkState);
    }

    @Test
    public void onePhase() {
        onePhase(junitState);
    }

    @Benchmark
    public boolean twoPhase(BenchmarkState benchmarkState) {
        return super.twoPhase(benchmarkState);
    }

    @Test
    public void twoPhase() {
        twoPhase(junitState);
    }

    @Benchmark
    public boolean twoPhaseNoWork(BenchmarkState benchmarkState) {
        return super.twoPhaseNoWork(benchmarkState);
    }

    @Test
    public void twoPhaseNoWork() {
        twoPhaseNoWork(junitState);
    }

    @Benchmark
    public boolean synch(BenchmarkState benchmarkState) {
        return super.synch(benchmarkState);
    }

    @Test
    public void synch() {
        synch(junitState);
    }
}
