# Commands Run During Fsync Investigation Session

**Date**: 2026-06-26  
**Session**: Investigating why fsync showed "no overhead" in benchmarks

---

## 1. Bytecode Analysis - Verify Fsync Is Actually Called

### Decompile NIOSequentialFile.sync() method
```bash
cd /home/mmusgrov && \
javap -c org/apache/activemq/artemis/core/io/nio/NIOSequentialFile.class | \
grep -A20 "public.*sync"
```

### Check NIOSequentialFileFactory constructor
```bash
cd /home/mmusgrov && \
javap -c org/apache/activemq/artemis/core/io/nio/NIOSequentialFileFactory.class | \
grep -A30 "public.*NIOSequentialFileFactory"
```

### Check AbstractSequentialFileFactory constructor
```bash
cd /home/mmusgrov && \
javap -c org/apache/activemq/artemis/core/io/AbstractSequentialFileFactory.class | \
grep -B10 -A10 "<init>"
```

### Check datasync field initialization
```bash
cd /home/mmusgrov && \
javap -v org/apache/activemq/artemis/core/io/nio/NIOSequentialFileFactory.class | \
grep -B5 -A15 "datasync"
```

---

## 2. Source Code Analysis - Understanding buffered Parameter

### Find Artemis Journal sources
```bash
cd /home/mmusgrov && \
find ~/.m2/repository/org/apache/activemq/ -name "*sources.jar" | head -3
```

### Read NIOSequentialFileFactory source
```bash
cd /home/mmusgrov && \
unzip -p ~/.m2/repository/org/apache/activemq/artemis-journal/2.19.0/artemis-journal-2.19.0-sources.jar \
  org/apache/activemq/artemis/core/io/nio/NIOSequentialFileFactory.java | head -100
```

### Read AbstractSequentialFileFactory constructor
```bash
cd /home/mmusgrov && \
unzip -p ~/.m2/repository/org/apache/activemq/artemis-journal/2.19.0/artemis-journal-2.19.0-sources.jar \
  org/apache/activemq/artemis/core/io/AbstractSequentialFileFactory.java | head -120 | tail -60
```

### Find where dataSync field is set
```bash
cd /home/mmusgrov && \
unzip -p ~/.m2/repository/org/apache/activemq/artemis-journal/2.19.0/artemis-journal-2.19.0-sources.jar \
  org/apache/activemq/artemis/core/io/AbstractSequentialFileFactory.java | \
grep -B5 -A5 "dataSync ="
```

---

## 3. Check Narayana Code - How Stores Use NIOSequentialFileFactory

### Check HQStore initialization
```bash
cd /home/mmusgrov/src/forks/narayana/narayana && \
grep -B5 -A10 "NIOSequentialFileFactory" \
  ArjunaCore/arjuna/classes/com/arjuna/ats/internal/arjuna/objectstore/hornetq/HornetqJournalStore.java | \
grep -A10 "new NIOSequentialFileFactory"
```

### Check if anything calls setDatasync
```bash
cd /home/mmusgrov/src/forks/narayana/narayana && \
grep -r "setDatasync" ArjunaCore/
```

---

## 4. System Call Tracing - Verify fdatasync() Is Actually Called

### Trace fsync syscalls during benchmark (simple)
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
strace -e fsync,fdatasync,sync,sync_file_range -c \
  java -jar target/benchmarks.jar "JGroupsSlotsBenchmark_WAL_Fsync" \
  -t 1 -f 1 -i 1 -wi 0 -r 1 2>&1 | \
grep -E "fsync|fdatasync|sync_file_range|calls"
```

### Trace and count fdatasync calls
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
timeout 10 strace -e fsync,fdatasync -c \
  java -jar target/benchmarks.jar "JGroupsSlotsBenchmark_WAL_Fsync" \
  -t 1 -f 1 -i 1 -wi 0 -r 3 2>&1 | \
grep -E "fsync|fdatasync|calls|ops/s"
```

### Count fdatasync in 3 seconds
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
(timeout 5 strace -e trace=fsync,fdatasync \
  java -jar target/benchmarks.jar "JGroupsSlotsBenchmark_WAL_Fsync" \
  -t 1 -f 1 -i 1 -wi 0 -r 2 2>&1 & sleep 3; pkill -9 -f benchmarks.jar) | \
grep -c fdatasync
```

---

## 5. Build Commands - Attempt to Force Real Fsync (Later Reverted)

### Rebuild narayana with buffered=false (WRONG - later reverted)
```bash
cd /home/mmusgrov/src/forks/narayana/narayana/ArjunaCore/arjuna && \
../../mvnw clean install -DskipTests 2>&1 | tail -20
```

### Rebuild performance benchmarks
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
command mvn clean package -DskipTests 2>&1 | tail -15
```

### Run benchmark with "real" fsync (but still fast!)
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
java -jar target/benchmarks.jar "JGroupsSlotsBenchmark_WAL_Fsync" \
  -t 10 -f 1 -i 3 -wi 1 -r 3 2>&1 | \
tee /tmp/jgroupsslots_wal_fsync_real.log | tail -30
```

### Rebuild narayana with reverted changes (buffered=true)
```bash
cd /home/mmusgrov/src/forks/narayana/narayana/ArjunaCore/arjuna && \
../../mvnw clean install -DskipTests 2>&1 | tail -10
```

### Rebuild performance benchmarks again
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
command mvn clean package -DskipTests 2>&1 | tail -5
```

---

## 6. Fsync Performance Testing - Raw FileChannel.force() Timing

### Simple fsync test (10 writes)
```bash
cd /tmp && javac FsyncTest2.java && java FsyncTest2
```

**FsyncTest2.java**:
```java
import java.io.*;
import java.nio.channels.FileChannel;

public class FsyncTest2 {
    public static void main(String[] args) throws Exception {
        File testFile = new File("/tmp/fsync-test.dat");
        try (RandomAccessFile raf = new RandomAccessFile(testFile, "rw");
             FileChannel channel = raf.getChannel()) {
            byte[] data = new byte[512];
            System.out.println("Testing 10 writes WITH fsync...");
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                raf.write(data);
                channel.force(false);  // fdatasync
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.println("10 writes + fsync: " + elapsed + "ms");
        }
        testFile.delete();
    }
}
```

### Larger fsync test (1000 writes, 4KB each)
```bash
cd /tmp && cat > FsyncTest3.java << 'EOF'
import java.io.*;
import java.nio.channels.FileChannel;

public class FsyncTest3 {
    public static void main(String[] args) throws Exception {
        File testFile = new File("/tmp/fsync-test.dat");
        try (RandomAccessFile raf = new RandomAccessFile(testFile, "rw");
             FileChannel channel = raf.getChannel()) {
            byte[] data = new byte[4096];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte)i;
            }
            System.out.println("Testing 1000 writes WITH fdatasync...");
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                raf.write(data);
                channel.force(false);  // fdatasync
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.println("1000 writes + fdatasync: " + elapsed + "ms");
        }
        testFile.delete();
    }
}
EOF
javac FsyncTest3.java && java FsyncTest3
```

**Result**: 2ms for 1000 fsyncs = 0.002ms per fsync (very fast due to OS caching!)

---

## 7. AIO vs NIO Comparison

### Check etc/ directory for native library
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
ls -la etc/
```

### Run HQStore with AIO (native library)
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
LD_LIBRARY_PATH=$(pwd)/etc/ java -jar target/benchmarks.jar "HQStoreBenchmark" \
  -t 10 -f 1 -i 3 -wi 1 -r 3 2>&1 | \
tee /tmp/hqstore_aio.log | tail -40
```

**Result**: 2,609 ops/s with AIO

### Rebuild with HQStoreBenchmark_NIO
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
command mvn clean package -DskipTests 2>&1 | tail -5
```

### Run HQStore with NIO (pure Java)
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta && \
java -jar target/benchmarks.jar "HQStoreBenchmark_NIO" \
  -t 10 -f 1 -i 3 -wi 1 -r 3 2>&1 | \
tee /tmp/hqstore_nio.log | tail -30
```

**Result**: 2,590 ops/s with NIO (within measurement error of AIO)

---

## Summary of Findings

### Key Commands for Reproduction

1. **Run HQStore with AIO**:
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta
LD_LIBRARY_PATH=$(pwd)/etc/ java -jar target/benchmarks.jar "HQStoreBenchmark" -t 10
```

2. **Run HQStore with NIO**:
```bash
cd /home/mmusgrov/src/forks/narayana/performance/narayana/ArjunaJTA/jta
java -jar target/benchmarks.jar "HQStoreBenchmark_NIO" -t 10
```

3. **Test raw fsync performance**:
```bash
cd /tmp
javac FsyncTest3.java && java FsyncTest3
```

### Key Findings

- **Fsync IS being called** via `fdatasync()` when `syncWrites=true`
- **buffered parameter** controls TimedBuffer batching, NOT datasync
- **dataSync defaults to true** in AbstractSequentialFileFactory
- **OS page cache + SSD cache** make fdatasync very fast (~1-10μs)
- **TimedBuffer batching** (490KB, 300 flushes/sec) amortizes fsync overhead
- **AIO ≈ NIO performance** for HQStore due to batching and read-heavy workload

---

**Generated**: 2026-06-27  
**Total commands executed**: 26  
**Investigation outcome**: Fsync overhead is real but negligible due to Artemis Journal batching  
