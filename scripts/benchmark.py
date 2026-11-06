#!/usr/bin/env python3
"""Bounded, sequential, fresh-JVM engine baseline. Run only while other heavy checks are idle."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from generate_orders import CASES, DEFAULT_SEED, SIZES, generate_dataset

ROOT = Path(__file__).resolve().parent.parent


def run_java_process(command: list[str], stdout_path: Path, stderr_path: Path, timeout: float = 90) -> dict:
    """One child only. wait4 returns that child's own RSS high-water mark, unlike RUSAGE_CHILDREN.

    Shared by optional database benchmarks. No shell, no dependency download, no parent-process RSS.
    A timeout kills only the process group created by this function and reaps the child.
    """
    if not hasattr(os, "wait4") or platform.system() not in ("Darwin", "Linux"):
        raise RuntimeError("Per-child RSS measurement currently supports macOS/Linux only")
    if not 0 < timeout <= 90:
        raise ValueError("Each child timeout must be between 0 and 90 seconds")
    started = time.perf_counter()
    timed_out = False
    with Path(stdout_path).open("x", encoding="utf-8") as stdout, Path(stderr_path).open("x", encoding="utf-8") as stderr:
        child = subprocess.Popen(command, cwd=ROOT, stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            while True:
                pid, status, usage = os.wait4(child.pid, os.WNOHANG)
                if pid:
                    child.returncode = os.waitstatus_to_exitcode(status)
                    break
                if time.perf_counter() - started >= timeout:
                    timed_out = True
                    try:
                        os.killpg(child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    _, status, usage = os.wait4(child.pid, 0)
                    child.returncode = os.waitstatus_to_exitcode(status)
                    break
                time.sleep(0.02)
        except BaseException:
            if child.returncode is None:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                _, status, _ = os.wait4(child.pid, 0)
                child.returncode = os.waitstatus_to_exitcode(status)
            raise
    return {
        "wallMillis": (time.perf_counter() - started) * 1000,
        "rssPeakBytes": usage.ru_maxrss * (1 if platform.system() == "Darwin" else 1024),
        "userCpuSeconds": usage.ru_utime, "systemCpuSeconds": usage.ru_stime,
        "exitCode": child.returncode, "timedOut": timed_out,
        "rssSource": "os.wait4/rusage.ru_maxrss (per-child OS high-water RSS)",
    }


def java_command(java_home: str | None = None) -> str:
    selected = java_home or os.environ.get("DATAGUARD_JAVA_HOME")
    configured = ROOT / ".setup/java21-home.txt"
    if not selected and configured.is_file():
        selected = configured.read_text(encoding="utf-8").strip()
    if not selected and Path("/usr/libexec/java_home").is_file():
        probe = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True, timeout=5)
        if probe.returncode == 0:
            selected = probe.stdout.strip()
    executable = str(Path(selected) / "bin/java") if selected else shutil.which("java")
    if not executable or not os.access(executable, os.X_OK):
        raise RuntimeError("Set DATAGUARD_JAVA_HOME to a JDK 21 directory")
    version = subprocess.run([executable, "-version"], capture_output=True, text=True, timeout=5)
    if version.returncode != 0 or 'version "21.' not in version.stderr + version.stdout:
        raise RuntimeError("The baseline requires Java 21; set DATAGUARD_JAVA_HOME explicitly")
    return executable


def source_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    for file in sorted(path.rglob("*")):
        if file.is_file():
            digest.update(file.relative_to(path).as_posix().encode())
            digest.update(b"\0")
            digest.update(file.read_bytes())
            digest.update(b"\0")
    return digest.hexdigest()


def verify_result(observed: dict, expected: dict) -> list[str]:
    failures = []
    keys = ("outcome", "errorCode", "lineNumber") if expected["outcome"] == "REJECTED" else (
        "outcome", "errorCode", "totalRecords", "validRecords", "invalidRecords", "totalIssues", "issuesByRule", "issuesBySeverity")
    for key in keys:
        if observed.get(key) != expected[key]:
            failures.append(f"{key}: expected {expected[key]!r}, observed {observed.get(key)!r}")
    if expected["outcome"] == "INSPECTED" and Decimal(str(observed.get("qualityScore"))) != Decimal(expected["qualityScore"]):
        failures.append("qualityScore does not match the generator's independent expectation")
    if observed.get("availableProcessors") != 2 or observed.get("heapMaxBytes", 0) > 256 * 1024 * 1024:
        failures.append("JVM resource settings do not match CPU=2 and Xmx=256m")
    if observed.get("limits", {}).get("records") != 10000:
        failures.append("Product record protection changed; 50000 must not run as an unlimited benchmark")
    return failures


def summarize(runs: list[dict]) -> list[dict]:
    groups = {}
    for run in runs:
        groups.setdefault((run["case"], run["records"]), []).append(run)
    summary = []
    for (case, records), group in groups.items():
        successful = [run for run in group if run["validationPassed"]]
        row = {"case": case, "records": records, "runs": len(group), "validatedRuns": len(successful)}
        if successful:
            for field in ("wallMillis", "engineMillis", "rssPeakBytes", "heapSampledPeakBytes"):
                values = [run[field] for run in successful]
                row[field] = {"min": min(values), "median": statistics.median(values), "max": max(values)}
            row["outcome"] = successful[0]["outcome"]
            row["errorCode"] = successful[0].get("errorCode")
        summary.append(row)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--classpath-file", type=Path, default=ROOT / "target/benchmark-classpath.txt")
    parser.add_argument("--java-home")
    parser.add_argument("--repeats", type=int, choices=(2, 3), default=3)
    parser.add_argument("--rows", type=int, nargs="+", choices=SIZES)
    parser.add_argument("--cases", nargs="+", choices=CASES)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--timeout-seconds", type=float, default=90)
    parser.add_argument("--total-timeout-seconds", type=float, default=600)
    args = parser.parse_args()
    if not 0 < args.timeout_seconds <= 90 or not 0 < args.total_timeout_seconds <= 900:
        parser.error("Per-JVM timeout must be <=90 seconds and total timeout <=900 seconds")
    java = java_command(args.java_home)
    harness = ROOT / "target/test-classes/dataguard/benchmark/EngineBenchmarkHarness.class"
    if not harness.is_file() or not args.classpath_file.is_file():
        parser.error("First compile test sources and create target/benchmark-classpath.txt; this tool never invokes Maven")
    dependencies = args.classpath_file.read_text(encoding="utf-8").strip()
    classpath = os.pathsep.join((str(ROOT / "target/test-classes"), str(ROOT / "target/classes"), dependencies))
    plan = list(dict.fromkeys((rows, case) for rows in (args.rows or SIZES) for case in (args.cases or ("normal", "mixed")))) \
        if args.rows or args.cases else [(1000, case) for case in CASES] + [(rows, case) for rows in (10000, 50000) for case in ("normal", "mixed")]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = (args.output_dir or ROOT / ".setup/benchmarks" / stamp).resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / "data").mkdir()
    runs = []
    report = {
        "schemaVersion": 1, "startedAt": datetime.now(timezone.utc).isoformat(),
        "host": {"os": platform.system(), "release": platform.release(), "architecture": platform.machine()},
        "java": java, "jvmOptions": ["-Xmx256m", "-XX:ActiveProcessorCount=2"],
        "repeats": args.repeats, "seed": args.seed,
        "coreSourceSha256": source_fingerprint(ROOT / "src/main/java/dataguard/core"),
        "coreClassesSha256": source_fingerprint(ROOT / "target/classes/dataguard/core"),
        "scope": "Cold, independent JVM engine inspections only; excludes database, API and report serialization",
        "memoryNotes": "heapSampledPeakBytes is a 10ms sampled JVM heap peak, not exact allocation or retained memory. rssPeakBytes is OS process high-water RSS, including native/JIT/metaspace memory. No forced GC.",
        "runs": runs,
    }
    deadline = time.perf_counter() + args.total_timeout_seconds
    all_valid = True
    try:
        for rows, case in plan:
            fixture = output / "data" / f"orders-{rows}-{case}.csv"
            expected = generate_dataset(fixture, rows, case, args.seed)
            for repeat in range(1, args.repeats + 1):
                remaining = deadline - time.perf_counter()
                if remaining <= 0:
                    raise TimeoutError("Overall benchmark budget exhausted")
                stem = output / f"{rows}-{case}-{repeat}"
                command = [java, "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp", classpath,
                           "dataguard.benchmark.EngineBenchmarkHarness", str(fixture)]
                measurement = run_java_process(command, stem.with_suffix(".stdout.json"), stem.with_suffix(".stderr.log"),
                                               min(args.timeout_seconds, remaining))
                observed = {}
                problems = []
                if measurement["exitCode"] == 0 and not measurement["timedOut"]:
                    try:
                        observed = json.loads(stem.with_suffix(".stdout.json").read_text(encoding="utf-8"))
                        problems = verify_result(observed, expected["expectation"])
                    except (ValueError, TypeError) as invalid:
                        problems = [f"Invalid harness output: {invalid}"]
                else:
                    problems = [f"Child exited {measurement['exitCode']}; timeout={measurement['timedOut']}"]
                run = {"case": case, "records": rows, "repeat": repeat, "inputSha256": expected["sha256"],
                       "sizeBytes": expected["sizeBytes"], **measurement, **observed,
                       "validationPassed": not problems, "validationErrors": problems}
                runs.append(run)
                print(f"{rows:5d} {case:9s} #{repeat}: {observed.get('outcome', 'FAILED')} "
                      f"wall={measurement['wallMillis']:.1f}ms RSS={measurement['rssPeakBytes']/1048576:.1f}MiB "
                      f"validated={not problems}", flush=True)
                if problems:
                    all_valid = False
                    raise RuntimeError("Benchmark observation failed its independent correctness expectation")
    except (OSError, RuntimeError, TimeoutError) as failure:
        all_valid = False
        report["failure"] = str(failure)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        report["allRunsValidated"] = all_valid and len(runs) == len(plan) * args.repeats
        report["summary"] = summarize(runs)
        with (output / "results.json").open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
        print(f"Results: {output / 'results.json'}", flush=True)
    return 0 if report["allRunsValidated"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError) as failure:
        print(f"Benchmark setup failed: {failure}", file=sys.stderr)
        raise SystemExit(2)
