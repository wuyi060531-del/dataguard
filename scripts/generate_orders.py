#!/usr/bin/env python3
"""Generate deterministic, fictional CSV fixtures; never overwrite an existing file."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
from collections import Counter
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

FIELDS = ("order_id", "customer_id", "product_id", "quantity", "unit_price", "order_time", "status")
CASES = ("normal", "duplicate", "missing", "date", "range", "type", "mixed")
SIZES = (1000, 10000, 50000)
DEFAULT_SEED = 20261030
MAX_RECORDS = 10000


def generate_dataset(output: Path, rows: int, case: str, seed: int = DEFAULT_SEED) -> dict:
    """Return an independently derived expectation manifest, including deliberate mutations."""
    if rows not in SIZES or case not in CASES:
        raise ValueError("rows must be 1000/10000/50000 and case must be a supported case")
    output = Path(output)
    manifest_path = output.with_suffix(output.suffix + ".manifest.json")
    if output.exists() or manifest_path.exists():
        raise FileExistsError(f"Refusing to overwrite an existing fixture or manifest: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    rng = random.Random(seed)
    orders = []
    base_time = datetime(2026, 1, 1)
    for index in range(rows):
        cents = rng.randint(100, 100000)
        orders.append({
            "order_id": f"O{index + 1:08d}",
            "customer_id": f"C{rng.randint(1, 100):04d}",
            "product_id": f"P{rng.randint(1, 100):04d}",
            "quantity": str(rng.randint(1, 10)),
            "unit_price": f"{cents // 100}.{cents % 100:02d}",
            "order_time": (base_time + timedelta(seconds=index)).isoformat(timespec="seconds"),
            "status": rng.choice(("PENDING", "PAID", "SHIPPED", "CANCELLED")),
        })
    targets = [] if case == "normal" else sorted(rng.sample(range(rows), rows // 20))
    mixed = ("missing", "duplicate", "date", "range", "type", "status", "amount")
    planned = [(index, mixed[number % len(mixed)] if case == "mixed" else case)
               for number, index in enumerate(targets)]
    target_set = set(targets)
    duplicate_count = sum(kind == "duplicate" for _, kind in planned)
    sources = iter(rng.sample([index for index in range(rows) if index not in target_set], duplicate_count))
    issues = Counter()
    invalid_records = set()
    injections = []
    warnings = 0
    for number, (index, kind) in enumerate(planned):
        record = orders[index]
        invalid_records.add(index)
        injection = {"recordNumber": index + 1, "kind": kind}
        if kind == "duplicate":
            source = next(sources)
            record["order_id"] = orders[source]["order_id"]
            invalid_records.add(source)
            issues["DUPLICATE_ORDER"] += 2
            injection["sourceRecordNumber"] = source + 1
        elif kind == "missing":
            field = FIELDS[number % len(FIELDS)]
            record[field] = " " if number % 2 else ""
            issues["REQUIRED_FIELD"] += 1
            injection["field"] = field
        elif kind == "date":
            record["order_time"] = "2026-02-30T12:00:00"
            issues["DATE_VALIDITY"] += 1
        elif kind == "range":
            field = "quantity" if number % 2 else "unit_price"
            record[field] = "0" if field == "quantity" else "-1.00"
            issues["NUMERIC_RANGE"] += 1
            injection["field"] = field
        elif kind == "type":
            field = "quantity" if number % 2 else "unit_price"
            record[field] = "two" if field == "quantity" else "NaN"
            issues["FIELD_TYPE"] += 1
            injection["field"] = field
        elif kind == "status":
            record["status"] = "UNKNOWN"
            issues["STATUS_ALLOWED"] += 1
        elif kind == "amount":
            record["quantity"], record["unit_price"] = "11", "10000.00"
            issues["TOTAL_AMOUNT_POLICY"] += 1
            warnings += 1
        injections.append(injection)
    with output.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(orders)
    data = output.read_bytes()
    total_issues = sum(issues.values())
    valid = rows - len(invalid_records)
    expectation = {
        "outcome": "INSPECTED" if rows <= MAX_RECORDS else "REJECTED",
        "errorCode": None if rows <= MAX_RECORDS else "TOO_MANY_RECORDS",
        "lineNumber": None if rows <= MAX_RECORDS else MAX_RECORDS + 2,
        "totalRecords": rows, "validRecords": valid, "invalidRecords": len(invalid_records),
        "totalIssues": total_issues,
        "qualityScore": str((Decimal(valid) * 100 / rows).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)),
        "issuesByRule": dict(sorted(issues.items())),
        "issuesBySeverity": {"ERROR": total_issues - warnings, "WARNING": warnings},
    }
    manifest = {
        "schemaVersion": 1, "generator": "scripts/generate_orders.py", "seed": seed,
        "case": case, "records": rows, "fields": list(FIELDS), "encoding": "UTF-8",
        "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
        "injectedTargetRecords": len(targets), "injections": injections,
        "expectation": expectation,
        "note": "Expected row/issue counts describe the full generated data; 50000-row inputs must be rejected by the unchanged 10000-row product limit.",
    }
    with manifest_path.open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, choices=SIZES, required=True)
    parser.add_argument("--case", choices=CASES, default="normal")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = generate_dataset(args.output, args.rows, args.case, args.seed)
    except (ValueError, OSError) as failure:
        parser.exit(2, f"Fixture generation failed: {failure}\n")
    print(json.dumps({key: manifest[key] for key in ("case", "records", "seed", "sizeBytes", "sha256")}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
