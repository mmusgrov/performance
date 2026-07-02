/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance.microbenchmarks;

import org.apache.activemq.artemis.core.journal.RecordInfo;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micro-benchmark measuring RecordInfo object creation overhead.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RecordInfoOverheadBenchmark {

    private AtomicLong idGenerator;
    private byte[] testData;
    private static final byte RECORD_TYPE = 0x00;

    @Setup(Level.Trial)
    public void setup() {
        idGenerator = new AtomicLong(0);
        testData = new byte[512];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte)i;
        }
    }

    // Measure RecordInfo creation (HQStore does this on every write)
    @Benchmark
    @Threads(10)
    public void createRecordInfo(Blackhole bh) {
        long id = idGenerator.getAndIncrement();
        RecordInfo record = new RecordInfo(id, RECORD_TYPE, testData, false, true, (short)0);
        bh.consume(record);
    }

    // Measure RecordInfo access pattern (HQStore does this on reads)
    @Benchmark
    @Threads(10)
    public void accessRecordInfo(Blackhole bh) {
        long id = idGenerator.getAndIncrement();
        RecordInfo record = new RecordInfo(id, RECORD_TYPE, testData, false, true, (short)0);

        // Access fields (simulating what HQStore does)
        long recordId = record.id;
        byte[] data = record.data;

        bh.consume(recordId);
        bh.consume(data);
    }

    // For comparison: direct byte[] copy (no wrapper object, simulates JGroupsSlots byte[] handling)
    @Benchmark
    @Threads(10)
    public void directByteArrayAccess(Blackhole bh) {
        byte[] copy = new byte[testData.length];
        System.arraycopy(testData, 0, copy, 0, testData.length);
        bh.consume(copy);
    }
}
