#!/bin/bash
#
# Compare JGroups stores against ShadowNoFileLockStore
#
# Usage: ./run-comparison.sh [threads]
#   threads: number of threads to use (default: 50 for faster testing)

THREADS=${1:-50}
ITERATIONS=3
WARMUP_ITERATIONS=1
TIME_PER_ITER=2

echo "======================================"
echo "JGroups Slot Stores Benchmark"
echo "======================================"
echo "Threads: $THREADS"
echo "Measurement iterations: $ITERATIONS"
echo "Warmup iterations: $WARMUP_ITERATIONS"
echo "Time per iteration: ${TIME_PER_ITER}s"
echo "======================================"
echo ""

# Set JMH args for thread count
export JMHARGS="-t $THREADS"

# Run benchmarks
java -jar target/benchmarks.jar \
  "(ShadowNoFileLockStoreBenchmark|JGroupsSlotsBenchmark|JGroupsRaftSlotsBenchmark)" \
  -t $THREADS \
  -f 1 \
  -i $ITERATIONS \
  -wi $WARMUP_ITERATIONS \
  -r $TIME_PER_ITER \
  -rf json \
  -rff benchmark-results.json

echo ""
echo "======================================"
echo "Results saved to: benchmark-results.json"
echo "======================================"
