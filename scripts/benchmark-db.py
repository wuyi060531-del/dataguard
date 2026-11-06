#!/usr/bin/env python3
"""Compare existing JDBC batches with/without MySQL wire-level rewriting, sequentially."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import statistics
import sys

from benchmark import ROOT, java_command, run_java_process
from generate_orders import generate_dataset, DEFAULT_SEED


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    base_url = os.environ.get("DATAGUARD_MYSQL_BENCH_URL", "")
    if not base_url.startswith("jdbc:mysql://127.0.0.1:") or "/dataguard_benchmark?" not in base_url:
        parser.error("Require the project-owned loopback dataguard_benchmark database environment")
    if "rewriteBatchedStatements=" in base_url:
        parser.error("Baseline URL must not preconfigure batch rewriting")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = output / "orders-10000-duplicate.csv"
    expected = generate_dataset(fixture, 10000, "duplicate", DEFAULT_SEED)
    classpath = os.pathsep.join((str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                                 (ROOT / "target/benchmark-classpath.txt").read_text().strip()))
    original = {key: os.environ.get(key) for key in ("DATAGUARD_MYSQL_TEST_URL", "DATAGUARD_MYSQL_TEST_USERNAME", "DATAGUARD_MYSQL_TEST_PASSWORD")}
    measurements = []
    try:
        for rewrite in (False, True):
            label = "rewritten" if rewrite else "baseline"
            os.environ["DATAGUARD_MYSQL_TEST_URL"] = base_url + "&rewriteBatchedStatements=" + str(rewrite).lower()
            for suffix in ("USERNAME", "PASSWORD"):
                os.environ["DATAGUARD_MYSQL_TEST_" + suffix] = os.environ["DATAGUARD_MYSQL_BENCH_" + suffix]
            result_file = output / (label + ".json")
            command = [java_command(), "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp", classpath,
                       "dataguard.benchmark.PersistenceBenchmark", str(fixture), str(result_file)]
            process = run_java_process(command, output / (label + ".stdout.log"), output / (label + ".stderr.log"), 90)
            if process["exitCode"] or process["timedOut"]:
                raise RuntimeError("Database benchmark failed; inspect local logs, no success is recorded")
            result = json.loads(result_file.read_text())
            if result["issues"] != expected["expectation"]["totalIssues"]:
                raise RuntimeError("Database benchmark did not match independent issue expectation")
            result.update(label=label, process=process,
                          medianTransactionMillis=statistics.median(x["transactionMillis"] for x in result["samples"]))
            measurements.append(result)
            print(label + ": " + str(result["issues"]) + " issues; median transaction "
                  + format(result["medianTransactionMillis"], ".2f") + " ms", flush=True)
    finally:
        for key, value in original.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
    report = {"measuredAt": datetime.now(timezone.utc).isoformat(), "measurements": measurements,
              "inputSha256": expected["sha256"], "inputBytes": expected["sizeBytes"],
              "note": "One fresh JVM per configuration; one warmup plus three measured transactions. Existing repository batch size stays 250. No API throughput claim."}
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
