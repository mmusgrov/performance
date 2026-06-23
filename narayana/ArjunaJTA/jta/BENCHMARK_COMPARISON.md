# JGroups Slot Store Benchmark Comparison

## Baseline Results

**Date**: 2026-06-23  
**Branch**: JBTM-4038  
**Configuration**: 10 threads, 2 iterations x 2 seconds  

### ShadowNoFileLockStoreBenchmark

```
Benchmark                                                  Mode  Cnt     Score   Error  Units
ShadowNoFileLockStoreBenchmark.testShadowNoFileLockStore  thrpt    2  5817.552          ops/s
```

**Throughput**: 5,817.5 ops/s (transactions per second)

**Characteristics**:
- File-based shadow copy store
- No file locking (faster than regular ShadowStore)
- Synchronous disk writes
- Baseline for comparison

## JGroups Benchmarks (Pending Build)

### JGroupsSlotsBenchmark
**Configuration**: WAL disabled (in-memory only)

**Expected performance**:
- ~1-2ms write latency
- Higher throughput than Shadow store (no disk I/O)
- Estimated: 8,000-12,000 ops/s with 10 threads

**Trade-off**: No persistence - data lost on crash

### JGroupsRaftSlotsBenchmark  
**Configuration**: Single-node, fsync disabled (buffered log)

**Expected performance**:
- ~1-2ms write latency  
- Similar to JGroupsSlots
- Estimated: 7,000-10,000 ops/s with 10 threads

**Trade-off**: Built-in WAL but buffered (may lose recent data on power failure)

## Running the Comparison

Once the build completes:

```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta

# Run comparison with 10 threads
export JMHARGS="-t 10"
java -jar target/benchmarks.jar \
  "(ShadowNoFileLockStoreBenchmark|JGroupsSlotsBenchmark|JGroupsRaftSlotsBenchmark)" \
  -t 10 -f 1 -i 3 -wi 1 -r 2 \
  -rf json -rff comparison-results.json

# Results will be in comparison-results.json
```

## Performance Configurations to Test

### 1. JGroupsSlots - No WAL (Maximum Performance)
```java
configBean.setWalEnabled(false);
```
**Expected**: Fastest, but no persistence

### 2. JGroupsSlots - WAL without fsync (Balanced)
```java
configBean.setWalEnabled(true);
configBean.setWalSyncWrites(false);
```
**Expected**: Medium performance, buffered persistence

### 3. JGroupsSlots - WAL with fsync (Maximum Durability)
```java
configBean.setWalEnabled(true);
configBean.setWalSyncWrites(true);
```
**Expected**: Slower (~10-20ms latency), but crash-safe

### 4. JGroupsRaftSlots - No fsync (Current Config)
```java
configBean.setRaftLogFsync(false);
```
**Expected**: Similar to JGroupsSlots no-WAL

### 5. JGroupsRaftSlots - With fsync (Maximum Durability)
```java
configBean.setRaftLogFsync(true);
```
**Expected**: Similar to JGroupsSlots with WAL+fsync

## Expected Results Summary

| Store | WAL | Fsync | Expected ops/s | Latency | Durability |
|-------|-----|-------|----------------|---------|------------|
| ShadowNoFileLock | Built-in | Yes | **5,818** | ~5-10ms | ✅ Crash-safe |
| JGroupsSlots | No | N/A | ~10,000 | ~1ms | ❌ In-memory only |
| JGroupsSlots | Yes | No | ~5,000 | ~3ms | ⚠️ Buffered |
| JGroupsSlots | Yes | Yes | ~500-1,000 | ~15ms | ✅ Crash-safe |
| JGroupsRaftSlots | Built-in | No | ~8,000 | ~2ms | ⚠️ Buffered |
| JGroupsRaftSlots | Built-in | Yes | ~500-1,000 | ~15ms | ✅ Crash-safe |

## Analysis

**For maximum throughput** (no durability requirements):
- JGroupsSlots with WAL disabled: Best choice
- Expected improvement: ~70% faster than ShadowNoFileLock

**For balanced performance/durability**:
- JGroupsSlots or JGroupsRaft with buffered WAL
- Expected: Similar to ShadowNoFileLock
- Advantage: Data survives OS cache flush

**For maximum durability** (crash + power failure safe):
- JGroupsSlots or JGroupsRaft with fsync enabled
- Expected: 5-10x slower than Shadow
- Advantage: Zero data loss guarantee

## Build Status

To check if build is complete:
```bash
ls -lh /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta/target/benchmarks.jar

# List available benchmarks
java -jar target/benchmarks.jar -l | grep -E "JGroups|Shadow"
```

## Next Steps

1. ✅ Build benchmarks.jar with JGroups benchmarks
2. ⏳ Run baseline comparison (Shadow vs JGroups no-WAL)
3. ⏳ Run WAL performance tests (fsync on/off)
4. ⏳ Generate comparison charts
5. ⏳ Document findings

---

**Created**: 2026-06-23  
**Status**: Build in progress  
**Branch**: JBTM-4038
