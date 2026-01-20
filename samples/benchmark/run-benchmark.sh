#!/bin/bash

# Argus Overhead Benchmark Runner
# Runs baseline, argus-agent, and argus-server benchmarks and compares results

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=============================================================="
echo "  Argus Overhead Benchmark Suite"
echo "=============================================================="
echo ""
echo "Building project..."
cd "$ROOT_DIR"
./gradlew :argus-agent:build :argus-server:build :samples:benchmark:build -q

echo ""
echo "=============================================================="
echo "  Running Baseline (No Argus)"
echo "=============================================================="
./gradlew :samples:benchmark:runBaseline --no-daemon -q 2>&1 | tee /tmp/benchmark-baseline.txt

echo ""
echo "=============================================================="
echo "  Running with Argus Agent (No Server)"
echo "=============================================================="
./gradlew :samples:benchmark:runWithArgusAgent --no-daemon -q 2>&1 | tee /tmp/benchmark-agent.txt

echo ""
echo "=============================================================="
echo "  Running with Argus Agent + Server"
echo "=============================================================="
./gradlew :samples:benchmark:runWithArgusServer --no-daemon -q 2>&1 | tee /tmp/benchmark-server.txt

echo ""
echo "=============================================================="
echo "  COMPARISON SUMMARY"
echo "=============================================================="
echo ""

# Extract CSV lines
BASELINE=$(grep "^  baseline" /tmp/benchmark-baseline.txt 2>/dev/null || echo "baseline,0,0,0,0,0,0,0,0")
AGENT=$(grep "^  argus-agent" /tmp/benchmark-agent.txt 2>/dev/null || echo "argus-agent,0,0,0,0,0,0,0,0")
SERVER=$(grep "^  argus-server" /tmp/benchmark-server.txt 2>/dev/null || echo "argus-server,0,0,0,0,0,0,0,0")

# Parse values
BASELINE_THROUGHPUT=$(echo "$BASELINE" | cut -d',' -f2)
AGENT_THROUGHPUT=$(echo "$AGENT" | cut -d',' -f2)
SERVER_THROUGHPUT=$(echo "$SERVER" | cut -d',' -f2)

BASELINE_P99=$(echo "$BASELINE" | cut -d',' -f5)
AGENT_P99=$(echo "$AGENT" | cut -d',' -f5)
SERVER_P99=$(echo "$SERVER" | cut -d',' -f5)

echo "  Mode                  Throughput        p99 Latency"
echo "  -------------------- --------------- ---------------"
printf "  %-20s %'15.0f %15s ms\n" "Baseline" "$BASELINE_THROUGHPUT" "$BASELINE_P99"
printf "  %-20s %'15.0f %15s ms\n" "Argus Agent" "$AGENT_THROUGHPUT" "$AGENT_P99"
printf "  %-20s %'15.0f %15s ms\n" "Argus Agent+Server" "$SERVER_THROUGHPUT" "$SERVER_P99"
echo ""

# Calculate overhead percentages
if [ "$BASELINE_THROUGHPUT" != "0" ] && [ -n "$BASELINE_THROUGHPUT" ]; then
    AGENT_OVERHEAD=$(echo "scale=2; (1 - $AGENT_THROUGHPUT / $BASELINE_THROUGHPUT) * 100" | bc 2>/dev/null || echo "N/A")
    SERVER_OVERHEAD=$(echo "scale=2; (1 - $SERVER_THROUGHPUT / $BASELINE_THROUGHPUT) * 100" | bc 2>/dev/null || echo "N/A")

    echo "  [Overhead Analysis]"
    echo "  Argus Agent overhead:        ${AGENT_OVERHEAD}%"
    echo "  Argus Agent+Server overhead: ${SERVER_OVERHEAD}%"
fi

echo ""
echo "=============================================================="
echo "  Benchmark complete!"
echo "=============================================================="
