# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the Narayana Transaction Manager performance testing repository. It contains JMH (Java Microbenchmark Harness) benchmarks for testing the performance of Narayana's transaction processing components.

## Prerequisites

**Critical**: This repository requires the main Narayana project to be built and installed in your local Maven repository first. If benchmarks fail to build with missing Narayana dependencies:

```bash
# Clone and build Narayana main (outside this repo)
git clone https://github.com/jbosstm/narayana.git
cd narayana
./build.sh clean install
```

## Build Commands

**Build all benchmarks**:
```bash
mvn clean install -DskipTests
```

**Build specific module**:
```bash
cd ArjunaJTA/jta  # or ArjunaCore/arjuna, ArjunaJTS/jts, stm
mvn clean install -DskipTests
```

**Run checkstyle only**:
```bash
mvn checkstyle:checkstyle
```

## Running Benchmarks

Each module builds a `benchmarks.jar` in its `target/` directory containing benchmark code and dependencies.

**Run all benchmarks in a module**:
```bash
java -jar ./ArjunaJTA/jta/target/benchmarks.jar
```

**Run specific benchmark with options**:
```bash
java -jar ./ArjunaJTA/jta/target/benchmarks.jar \
  com.arjuna.ats.jta.xa.performance.VolatileStoreBenchmark.* \
  -i 1 -wi 2 -f 1 -t 2 -r 10
```

**Fail immediately on benchmark errors** (add `-foe true`):
```bash
java -jar ./ArjunaJTA/jta/target/benchmarks.jar \
  com.arjuna.ats.jta.xa.performance.VolatileStoreBenchmark.* \
  -i 1 -wi 2 -f 1 -t 2 -r 10 -foe true
```

**JMH option meanings**:
- `-i N`: Number of measurement iterations
- `-wi N`: Number of warmup iterations  
- `-f N`: Number of forks
- `-t N`: Number of threads
- `-r N`: Time in seconds for each iteration
- `-foe true`: Fail-on-error (stops on first benchmark failure)

**View available JMH options**:
```bash
java -jar ./ArjunaJTA/jta/target/benchmarks.jar -help
```

## Profiling

**Run with JFR (Java Flight Recorder)**:
```bash
java -XX:+FlightRecorder \
  -XX:StartFlightRecording=duration=200s,filename=flight.jfr \
  -jar ArjunaCore/arjuna/target/benchmarks.jar \
  com.hp.mwtests.ts.arjuna.performance.VTPerformanceTest
```

**Analyze JFR output**:
```bash
jfr print flight.jfr  # Command-line analysis
jmc                    # GUI tool (Java Mission Control)
```

Some benchmarks include a `main()` method to simplify running from IDEs or with profilers.

## Testing

**Run tests**:
```bash
mvn test
```

**Run specific test**:
```bash
mvn test -Dtest=TestBenchmark#testImprovement
```

**Debug a test**:
```bash
mvn clean test -Dtest=TestBenchmark#testImprovement -Dmaven.surefire.debug
```

## Module Structure

- **ArjunaCore/arjuna**: Core transaction engine benchmarks (Uid generation, atomic actions, object store performance)
- **ArjunaJTA/jta**: JTA (Java Transaction API) benchmarks testing various object store implementations:
  - `VolatileStoreBenchmark`: In-memory store
  - `ShadowNoFileLockStoreBenchmark`: File-based store without locking
  - `JDBCStoreBenchmark`: Database-backed store  
  - `HQStoreBenchmark`: ActiveMQ Artemis journal store
  - `DiskSlotsStoreBenchmark`: Disk-based slot allocation
  - `InfinispanSlotsStoreBenchmark`: Infinispan-backed store
- **ArjunaJTS/jts**: JTS (Java Transaction Service / distributed transactions) performance tests
- **stm**: Software Transactional Memory benchmarks
- **tools**: Test utilities (CSV to JSON converter for benchmark results)

## Architecture Notes

**Benchmark Structure**:
- Benchmarks use JMH `@Benchmark` annotations on test methods
- Common configuration is in `JMHConfigJTA` and `JMHConfigCore` classes
- Default config: 1 warmup iteration (1s), 2 measurement iterations (10s each), 1 fork, 1 thread
- Store benchmarks extend `JTAStoreBase` with specific store setup in `@Setup(Level.Trial)` methods

**Build Process**:
- Uses Maven Shade Plugin to create uber-jars (benchmarks.jar) with all dependencies
- JMH annotation processor runs at compile time to generate benchmark harness
- For JDK 23+, annotation processor must be explicitly configured (see pom.xml profile)

**Code Quality**:
- Checkstyle enforced using WildFly configuration (`wildfly-checkstyle/checkstyle.xml`)
- Can be disabled per-module with `<skip.checkstyle>true</skip.checkstyle>`

## Requirements

- **Java**: 25 (see `maven.compiler.source` and `maven.compiler.target` in pom.xml)
- **Maven**: 3.0+
- **Narayana**: Version 7.3.4.Final-SNAPSHOT must be in local Maven repository

## Writing New Benchmarks

1. Annotate methods with `@Benchmark`
2. Use `@State(Scope.Benchmark)` on the class for shared state
3. Use `@Setup(Level.Trial)` for one-time initialization
4. Accept `Blackhole bh` parameter and consume results: `bh.consume(result)`
5. Follow patterns in existing benchmarks (e.g., `VolatileStoreBenchmark.java`)
