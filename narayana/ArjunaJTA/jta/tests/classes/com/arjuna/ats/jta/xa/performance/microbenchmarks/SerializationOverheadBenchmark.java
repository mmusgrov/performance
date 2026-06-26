/*
 * Copyright The Narayana Authors
 * SPDX short identifier: Apache-2.0
 */

package com.arjuna.ats.jta.xa.performance.microbenchmarks;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.state.InputBuffer;
import com.arjuna.ats.arjuna.state.OutputBuffer;
import com.arjuna.ats.internal.arjuna.common.UidHelper;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark comparing serialization approaches.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SerializationOverheadBenchmark {

    private Uid testUid;
    private String testTypeName;
    private byte[] testState;
    private int testStateStatus;

    @Setup(Level.Trial)
    public void setup() {
        testUid = new Uid();
        testTypeName = "/StateManager/BasicAction/TwoPhaseCoordinator/AtomicAction";
        testState = new byte[512];
        testStateStatus = 0; // OS_COMMITTED

        // Fill with test data
        for (int i = 0; i < testState.length; i++) {
            testState[i] = (byte)i;
        }
    }

    // HQStore style: Pack Uid + typeName + state
    @Benchmark
    @Threads(10)
    public void hqstore_pack(Blackhole bh) throws IOException {
        OutputBuffer buffer = new OutputBuffer();
        UidHelper.packInto(testUid, buffer);
        buffer.packString(testTypeName);
        buffer.packBytes(testState);
        byte[] result = buffer.buffer();
        bh.consume(result);
    }

    // HQStore style: Unpack Uid + typeName + state
    @Benchmark
    @Threads(10)
    public void hqstore_unpack(Blackhole bh) throws IOException {
        // First pack it
        OutputBuffer outBuffer = new OutputBuffer();
        UidHelper.packInto(testUid, outBuffer);
        outBuffer.packString(testTypeName);
        outBuffer.packBytes(testState);
        byte[] packed = outBuffer.buffer();

        // Then unpack it
        InputBuffer inBuffer = new InputBuffer(packed);
        Uid uid = UidHelper.unpackFrom(inBuffer);
        String typeName = inBuffer.unpackString();
        byte[] state = inBuffer.unpackBytes();

        bh.consume(uid);
        bh.consume(typeName);
        bh.consume(state);
    }

    // JGroupsSlots style: Pack Uid + typeName + stateStatus + state
    @Benchmark
    @Threads(10)
    public void jgroupsslots_pack(Blackhole bh) throws IOException {
        OutputBuffer buffer = new OutputBuffer();
        UidHelper.packInto(testUid, buffer);
        buffer.packString(testTypeName);
        buffer.packInt(testStateStatus);
        buffer.packBytes(testState);
        byte[] result = buffer.buffer();
        bh.consume(result);
    }

    // SlotJournal style: Pack slotId + length + data into ByteBuffer
    @Benchmark
    @Threads(10)
    public void slotjournal_pack(Blackhole bh) {
        int slotId = 42;
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + testState.length);
        buffer.putInt(slotId);
        buffer.putInt(testState.length);
        buffer.put(testState);
        byte[] result = buffer.array();
        bh.consume(result);
    }

    // Combined: Full JGroupsSlots write path serialization
    @Benchmark
    @Threads(10)
    public void jgroupsslots_full_pack(Blackhole bh) throws IOException {
        // SlotStoreKey packing
        OutputBuffer keyBuffer = new OutputBuffer();
        UidHelper.packInto(testUid, keyBuffer);
        keyBuffer.packString(testTypeName);
        keyBuffer.packInt(testStateStatus);
        keyBuffer.packBytes(testState);
        byte[] keyData = keyBuffer.buffer();

        // SlotJournal packing
        int slotId = 42;
        ByteBuffer journalBuffer = ByteBuffer.allocate(4 + 4 + keyData.length);
        journalBuffer.putInt(slotId);
        journalBuffer.putInt(keyData.length);
        journalBuffer.put(keyData);
        byte[] result = journalBuffer.array();

        bh.consume(result);
    }
}
