/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance.microbenchmarks;

import com.arjuna.ats.arjuna.common.Uid;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark comparing HQStore's 2-level HashMap vs JGroupsSlots' 1-level HashMap.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class HashMapOverheadBenchmark {

    // HQStore style: 2-level ConcurrentHashMap
    private ConcurrentHashMap<String, ConcurrentHashMap<Uid, byte[]>> twoLevelMap;

    // JGroupsSlots style: 1-level ConcurrentHashMap
    private ConcurrentHashMap<String, byte[]> oneLevelMap;

    // Test data
    private String[] typeNames;
    private Uid[] uids;
    private byte[] testData;

    @Setup(Level.Trial)
    public void setup() {
        twoLevelMap = new ConcurrentHashMap<>();
        oneLevelMap = new ConcurrentHashMap<>();

        typeNames = new String[10];
        uids = new Uid[100];
        testData = new byte[1024];

        for (int i = 0; i < typeNames.length; i++) {
            typeNames[i] = "/StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction" + i;
            twoLevelMap.put(typeNames[i], new ConcurrentHashMap<>());
        }

        for (int i = 0; i < uids.length; i++) {
            uids[i] = new Uid();
        }

        // Pre-populate both maps with matching data so reads hit entries
        for (String typeName : typeNames) {
            for (Uid uid : uids) {
                twoLevelMap.get(typeName).put(uid, testData);
                oneLevelMap.put(typeName + "#" + uid.toString(), testData);
            }
        }
    }

    @Benchmark
    @Threads(10)
    public void twoLevelHashMap_write(Blackhole bh) {
        String typeName = typeNames[ThreadLocalRandom.current().nextInt(typeNames.length)];
        Uid uid = uids[ThreadLocalRandom.current().nextInt(uids.length)];

        ConcurrentHashMap<Uid, byte[]> innerMap = twoLevelMap.get(typeName);
        byte[] previous = innerMap.put(uid, testData);
        bh.consume(previous);
    }

    @Benchmark
    @Threads(10)
    public void oneLevelHashMap_write(Blackhole bh) {
        String typeName = typeNames[ThreadLocalRandom.current().nextInt(typeNames.length)];
        Uid uid = uids[ThreadLocalRandom.current().nextInt(uids.length)];

        String key = typeName + "#" + uid.toString();
        byte[] previous = oneLevelMap.put(key, testData);
        bh.consume(previous);
    }

    @Benchmark
    @Threads(10)
    public void twoLevelHashMap_read(Blackhole bh) {
        String typeName = typeNames[ThreadLocalRandom.current().nextInt(typeNames.length)];
        Uid uid = uids[ThreadLocalRandom.current().nextInt(uids.length)];

        ConcurrentHashMap<Uid, byte[]> innerMap = twoLevelMap.get(typeName);
        byte[] data = innerMap.get(uid);
        bh.consume(data);
    }

    @Benchmark
    @Threads(10)
    public void oneLevelHashMap_read(Blackhole bh) {
        String typeName = typeNames[ThreadLocalRandom.current().nextInt(typeNames.length)];
        Uid uid = uids[ThreadLocalRandom.current().nextInt(uids.length)];

        String key = typeName + "#" + uid.toString();
        byte[] data = oneLevelMap.get(key);
        bh.consume(data);
    }
}
