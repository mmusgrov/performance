# JGroups Slot Store Benchmarks

This directory contains JMH benchmarks for the JGroups-based slot stores.

## Available Benchmarks

### 1. JGroupsSlotsBenchmark
Tests **JGroupsSlots** store (ReplCache-based with optional WAL).

**Performance characteristics**:
- Without WAL: ~1-2ms write latency (in-memory cache only)
- With WAL + fsync: ~10-20ms write latency (disk persistence)
- With WAL no fsync: ~3-5ms write latency (buffered writes)

**Configuration** (in benchmark file):
```java
configBean.setWalEnabled(false);      // Toggle WAL on/off
configBean.setWalSyncWrites(false);   // Toggle fsync for writes
configBean.setCachingTime(0L);        // L2 cache TTL (0 = disabled)
```

### 2. JGroupsRaftSlotsBenchmark
Tests **JGroupsRaftSlots** store (Raft consensus with built-in persistent WAL).

**Performance characteristics** (single-node):
- With fsync: ~10-20ms write latency, 100-200 ops/sec (maximum durability)
- Without fsync: ~1-2ms write latency, 1000+ ops/sec (buffered writes)
- Read latency: ~0.1ms (local reads from state machine)

**Configuration** (in benchmark file):
```java
configBean.setRaftLogFsync(false);    // Toggle fsync for Raft log
configBean.setRaftMembers("node1");   // Single node for benchmark
```

**Note**: Production Raft requires 3+ nodes for fault tolerance. This benchmark runs single-node for performance testing only.

## Building

From the performance repo root:

```bash
cd /home/mmusgrov/src/forks/narayana/performance
git checkout JBTM-4038

# Build the benchmarks
cd narayana/ArjunaJTA/jta
mvn clean package
```

This creates `target/benchmarks.jar` containing all benchmarks.

## Running from IDE

Open the benchmark file (e.g., `JGroupsSlotsBenchmark.java`) and run the `main()` method.

The IDE run uses these defaults:
- Threads: 240
- Forks: 1
- Iterations: 5 warmup + 5 measurement
- Time per iteration: 2 seconds
- Mode: Throughput (ops/sec)
- Profiler: Java Flight Recorder (JFR)

## Running from Command Line

### Run all benchmarks:
```bash
java -jar target/benchmarks.jar
```

### Run specific benchmark:
```bash
# JGroupsSlots benchmark
java -jar target/benchmarks.jar JGroupsSlotsBenchmark

# JGroupsRaftSlots benchmark
java -jar target/benchmarks.jar JGroupsRaftSlotsBenchmark
```

### Custom thread count:
```bash
# Run with 100 threads
export JMHARGS="-t 100"
java -jar target/benchmarks.jar JGroupsSlotsBenchmark
```

### With custom JMH options:
```bash
java -jar target/benchmarks.jar JGroupsSlotsBenchmark \
  -t 100 \
  -f 1 \
  -i 10 \
  -wi 5 \
  -r 5 \
  -prof jfr
```

**Options**:
- `-t` = threads (default: 240)
- `-f` = forks (default: 1)
- `-i` = measurement iterations (default: 5)
- `-wi` = warmup iterations (default: 1)
- `-r` = time per iteration in seconds (default: 2)
- `-prof jfr` = enable Java Flight Recorder profiling

## Comparing Store Performance

Run all slot store benchmarks to compare:

```bash
java -jar target/benchmarks.jar ".*SlotStore.*Benchmark" -t 100
```

This runs:
- `DiskSlotsStoreBenchmark` - File-based slot store
- `InfinispanSlotsStoreBenchmark` - Infinispan-based slot store
- `JGroupsSlotsBenchmark` - JGroups ReplCache-based store
- `JGroupsRaftSlotsBenchmark` - JGroups Raft-based store

## Profiling with Java Flight Recorder

Benchmarks automatically enable JFR profiling. After running, find the `.jfr` file:

```bash
# Location: working directory
ls -la *.jfr

# View with jmc (Java Mission Control)
jmc profile.jfr

# Or with jfr command-line tool
jfr print profile.jfr
jfr summary profile.jfr
```

## Benchmark Configuration

Edit the benchmark `.java` files to change configuration:

### JGroupsSlotsBenchmark.java

**Enable WAL**:
```java
configBean.setWalEnabled(true);      // Enable WAL
configBean.setWalSyncWrites(true);   // Enable fsync (slower, safer)
```

**Enable L2 caching** (not recommended with WAL):
```java
configBean.setCachingTime(30000L);   // 30-second L2 cache
```

### JGroupsRaftSlotsBenchmark.java

**Enable fsync**:
```java
configBean.setRaftLogFsync(true);    // Enable fsync (slower, durable)
```

## Interpreting Results

**Throughput mode** (ops/sec):
- Higher is better
- Shows how many transactions/sec the store can handle

**Sample output**:
```
Benchmark                                    Mode  Cnt     Score     Error  Units
JGroupsSlotsBenchmark.testJGroupsSlotsStore  thrpt    5  1234.567 ± 45.678  ops/s
JGroupsRaftSlotsBenchmark.testJGroupsRaftSlotsStore  thrpt    5   456.789 ± 12.345  ops/s
DiskSlotsStoreBenchmark.testDiskSlotsStore   thrpt    5   789.012 ± 23.456  ops/s
```

**Interpretation**:
- JGroupsSlots (no WAL): Fastest, but no persistence
- JGroupsRaftSlots: Slower, but provides consensus + persistence
- DiskSlots: Baseline file-based store

## Performance Tuning

### For maximum throughput (no durability):
- `walEnabled=false` (JGroupsSlots)
- `raftLogFsync=false` (JGroupsRaftSlots)

### For maximum durability (slower):
- `walEnabled=true` + `walSyncWrites=true` (JGroupsSlots)
- `raftLogFsync=true` (JGroupsRaftSlots)

### For balanced performance:
- `walEnabled=true` + `walSyncWrites=false` (JGroupsSlots)
- `raftLogFsync=false` (JGroupsRaftSlots)

## Troubleshooting

### JGroups cluster formation issues
If benchmarks hang during setup, check JGroups config files in `etc/`:
- `jgroups.xml` - ReplCache configuration
- `jgroups-raft.xml` - Raft configuration

Both are configured for localhost testing with `SHARED_LOOPBACK`.

### Out of memory errors
Reduce thread count:
```bash
export JMHARGS="-t 50"
java -jar target/benchmarks.jar JGroupsSlotsBenchmark
```

### Slow benchmark execution
Reduce iterations:
```bash
java -jar target/benchmarks.jar JGroupsSlotsBenchmark -i 3 -wi 1 -r 1
```

## Files

**Benchmark classes**:
- `JGroupsSlotsBenchmark.java` - ReplCache-based store benchmark
- `JGroupsRaftSlotsBenchmark.java` - Raft-based store benchmark

**Configuration files** (in `etc/`):
- `jgroups.xml` - JGroups ReplCache configuration
- `jgroups-raft.xml` - JGroups Raft configuration

**Dependencies** (in `pom.xml`):
- `org.jgroups:jgroups` - JGroups core
- `org.jgroups:jgroups-raft` - JGroups Raft extension
- `org.apache.activemq:artemis-journal` - WAL for JGroupsSlots

## Related Documentation

- Main Narayana docs: `/home/mmusgrov/src/forks/narayana/narayana/ArjunaCore/arjuna/designs/`
- WAL implementation: `JGROUPS_WAL_IMPLEMENTATION_SUMMARY.md`
- Raft analysis: `JGROUPS_RAFT_WAL_ANALYSIS.md`

---

**Created**: 2026-06-23  
**Branch**: JBTM-4038  
**Related JIRA**: JBTM-4038
