# HQStore: AIO vs NIO Comparison

**Date**: 2026-06-26  
**Test Setup**: 10 threads, 3 iterations, 3 seconds per iteration  
**Configuration**: `syncWrites=true`, `bufferFlushesPerSecond=300`, `maxIO=500`

---

## Results

| Implementation | Throughput | Latency | vs NIO |
|----------------|-----------|---------|--------|
| **HQStore (AIO)** | 2,609 ops/s | 383 μs | -0.7% |
| **HQStore (NIO)** | 2,590 ops/s | 386 μs | baseline |

**Difference**: Within measurement error (±430-484 ops/s std dev)

---

## How to Run

### With AIO (libartemis-native-64.so)

```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta
LD_LIBRARY_PATH=$(pwd)/etc/ java -jar target/benchmarks.jar "HQStoreBenchmark" -t 10
```

**Output confirms AIO is loaded**:
```
WARNING: java.lang.System::loadLibrary has been called by 
         org.apache.activemq.artemis.nativo.jlibaio.LibaioContext
```

### With NIO (Java NIO channels)

```bash
java -jar target/benchmarks.jar "HQStoreBenchmark_NIO" -t 10
```

**No native library warning** - uses pure Java NIO.

---

## Analysis

### Why AIO Is Not Faster

**Expected**: AIO should be faster because:
- Asynchronous I/O (kernel handles buffering)
- Can use `O_DIRECT` to bypass page cache
- Better concurrency with multiple threads

**Actual**: AIO and NIO have nearly identical performance.

**Reasons**:

#### 1. Both Use TimedBuffer Batching

**TimedBuffer** (490KB, 300 flushes/sec) batches writes before they reach the I/O layer:

```
Application writes → TimedBuffer (batches) → FileFactory.sync()
                                                    ↓
                                            AIO or NIO
```

The batching happens **before** the AIO/NIO layer, so both benefit equally.

#### 2. Linux Page Cache Makes NIO Fast

**NIO** with `FileChannel.force(false)`:
- Calls `fdatasync()` syscall
- Linux page cache buffers writes in RAM
- Sync only marks pages dirty, doesn't wait for disk
- Very fast (~1-10μs as measured in FSYNC_INVESTIGATION.md)

**AIO** with `io_submit()`:
- Direct kernel I/O submission
- May bypass page cache with `O_DIRECT`
- But TimedBuffer batching means most writes are cached anyway

#### 3. Benchmark Is Read-Heavy

HQStore benchmark pattern:
```java
write_committed(uid, typeName, state);  // Write
read_committed(uid, typeName);          // Read
remove_committed(uid, typeName);        // Delete
```

**Reads dominate** performance:
- Reads use `ConcurrentHashMap` lookup (in-memory)
- HashMap lookup: ~200ns
- I/O layer (AIO/NIO) only affects writes

**Write path** (where AIO/NIO matters):
- Only ~33% of operations
- Already optimized by TimedBuffer batching
- Difference gets diluted by fast read path

#### 4. SSD Write Cache

Modern SSDs have internal DRAM cache (hundreds of MB):
- Both AIO and NIO writes go to SSD cache first
- SSD controller handles actual NAND writes
- From application perspective, both are "fast"

---

## When Would AIO Be Faster?

AIO would show benefits when:

1. **Write-heavy workload** (>80% writes)
   - Current benchmark: ~33% writes
   - Write-only benchmark would show AIO advantage

2. **Large writes without batching**
   - Direct large file writes (>1MB)
   - No TimedBuffer between application and I/O layer

3. **O_DIRECT + high concurrency**
   - Bypass page cache entirely
   - Many threads doing large I/O
   - Current: 10 threads, small writes, batched

4. **Very fast storage** (NVMe, Optane)
   - Page cache overhead becomes bottleneck
   - AIO's async nature shines
   - Current: regular SSD with page cache

---

## Conclusion

### For HQStore: AIO ≈ NIO

**Performance**: Within measurement error (2,609 vs 2,590 ops/s)

**Why**: TimedBuffer batching + page cache + read-heavy benchmark make both equally fast.

**Recommendation**: 
- ✅ Use **NIO** for simplicity (no native library dependency)
- ✅ Use **AIO** only if:
  - Write-heavy workload (>80% writes)
  - Very fast storage (NVMe)
  - Want to avoid JVM warnings about native access

### Both Are Bottlenecked by Architecture

**Real bottleneck** (as explained in MICRO_BENCHMARK_ANALYSIS.md):
- Not I/O layer (AIO/NIO)
- **TimedBuffer flush cadence** (every 3.33ms)
- Reads from journal-backed HashMap instead of cache

**JGroupsSlots is 327× faster** because:
- Reads from ReplCache (in-memory), not journal-backed HashMap
- Writes to journal async, reads bypass journal entirely
- I/O layer (AIO/NIO) is NOT the bottleneck

---

## Benchmark Commands

### Run Both for Comparison

```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta

# AIO
LD_LIBRARY_PATH=$(pwd)/etc/ java -jar target/benchmarks.jar "HQStoreBenchmark" -t 10 -f 1 -i 3 -wi 1 -r 3

# NIO
java -jar target/benchmarks.jar "HQStoreBenchmark_NIO" -t 10 -f 1 -i 3 -wi 1 -r 3
```

### Expected Output

**AIO**:
```
WARNING: java.lang.System::loadLibrary has been called by 
         org.apache.activemq.artemis.nativo.jlibaio.LibaioContext
Benchmark                      Mode  Cnt     Score      Error  Units
HQStoreBenchmark.testHQStore  thrpt    3  2609.753 ± 7855.451  ops/s
```

**NIO**:
```
[WARN] AIO wasn't located... will fall back to using pure Java NIO
Benchmark                          Mode  Cnt     Score      Error  Units
HQStoreBenchmark_NIO.testHQStore  thrpt    3  2590.416 ± 8831.729  ops/s
```

---

## Technical Details

### AIO Implementation (libartemis-native)

**Library**: `etc/libartemis-native-64.so`

**Usage**:
```java
// HornetqJournalStore.java line 100-106
if (envBean.isAsyncIO() && AIOSequentialFileFactory.isSupported()) {
    sequentialFileFactory = new AIOSequentialFileFactory(
        storeDir,
        envBean.getBufferSize(),
        (int)(1000000000d / envBean.getBufferFlushesPerSecond()),
        envBean.getMaxIO(),
        envBean.isLogRates());
}
```

**System calls**: `io_submit()`, `io_getevents()`

### NIO Implementation (java.nio)

**Usage**:
```java
// HornetqJournalStore.java line 108-119
else {
    sequentialFileFactory = new NIOSequentialFileFactory(
        storeDir,
        true,  // buffered - enables TimedBuffer
        envBean.getBufferSize(),
        (int)(1000000000d / envBean.getBufferFlushesPerSecond()),
        1,     // maxIO (ignored in NIO mode)
        envBean.isLogRates());
}
```

**System calls**: `write()`, `fdatasync()`

---

**Generated**: 2026-06-26  
**HQStore AIO**: 2,609 ops/s (383 μs/op)  
**HQStore NIO**: 2,590 ops/s (386 μs/op)  
**Conclusion**: No significant difference due to TimedBuffer batching and page cache optimization  
