#!/usr/bin/env python3
"""Small optional tool tests; not a dependency of the Java application or its Maven tests."""
import csv
import tempfile
import unittest
from collections import Counter
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from generate_orders import CASES, FIELDS, generate_dataset


class GeneratorTest(unittest.TestCase):
    def test_same_seed_is_byte_reproducible_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            first = generate_dataset(base / "one.csv", 1000, "mixed")
            second = generate_dataset(base / "two.csv", 1000, "mixed")
            self.assertEqual((base / "one.csv").read_bytes(), (base / "two.csv").read_bytes())
            self.assertEqual(first, second)
            with self.assertRaises(FileExistsError):
                generate_dataset(base / "one.csv", 1000, "normal")
            self.assertEqual(first, second)

    def test_all_seven_cases_match_independently_recounted_quality_findings(self):
        with tempfile.TemporaryDirectory() as folder:
            for case in CASES:
                with self.subTest(case=case):
                    path = Path(folder) / f"{case}.csv"
                    manifest = generate_dataset(path, 1000, case)
                    with path.open(encoding="utf-8", newline="") as stream:
                        orders = list(csv.DictReader(stream))
                    ids = Counter(row["order_id"].strip() for row in orders if row["order_id"].strip())
                    counts, bad_records = Counter(), set()
                    warnings = 0
                    for index, raw in enumerate(orders):
                        row = {key: value.strip() for key, value in raw.items()}
                        findings = []
                        findings.extend("REQUIRED_FIELD" for field in FIELDS if not row[field])
                        if row["order_id"] and ids[row["order_id"]] > 1:
                            findings.append("DUPLICATE_ORDER")
                        quantity, price = None, None
                        if row["quantity"]:
                            try:
                                quantity = int(row["quantity"])
                                if not 1 <= quantity <= 100:
                                    findings.append("NUMERIC_RANGE")
                            except ValueError:
                                findings.append("FIELD_TYPE")
                        if row["unit_price"]:
                            try:
                                price = Decimal(row["unit_price"])
                                if not price.is_finite():
                                    price = None
                                    findings.append("FIELD_TYPE")
                                elif not Decimal("0.01") <= price <= Decimal("10000"):
                                    findings.append("NUMERIC_RANGE")
                            except ArithmeticError:
                                findings.append("FIELD_TYPE")
                        if row["order_time"]:
                            try:
                                datetime.strptime(row["order_time"], "%Y-%m-%dT%H:%M:%S")
                            except ValueError:
                                findings.append("DATE_VALIDITY")
                        if row["status"] and row["status"] not in ("PENDING", "PAID", "SHIPPED", "CANCELLED"):
                            findings.append("STATUS_ALLOWED")
                        if quantity is not None and price is not None and 1 <= quantity <= 100 \
                                and Decimal("0.01") <= price <= Decimal("10000") and quantity * price > 100000:
                            findings.append("TOTAL_AMOUNT_POLICY")
                            warnings += 1
                        counts.update(findings)
                        if findings:
                            bad_records.add(index)
                    expected = manifest["expectation"]
                    self.assertEqual(len(orders), 1000)
                    self.assertEqual(dict(counts), expected["issuesByRule"])
                    self.assertEqual(len(bad_records), expected["invalidRecords"])
                    self.assertEqual(sum(counts.values()), expected["totalIssues"])
                    self.assertEqual(warnings, expected["issuesBySeverity"]["WARNING"])

    def test_fifty_thousand_rows_fit_file_limit_but_expect_record_guard_rejection(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = generate_dataset(Path(folder) / "50000.csv", 50000, "mixed")
            self.assertLess(manifest["sizeBytes"], 5 * 1024 * 1024)
            self.assertEqual("REJECTED", manifest["expectation"]["outcome"])
            self.assertEqual("TOO_MANY_RECORDS", manifest["expectation"]["errorCode"])
            self.assertEqual(10002, manifest["expectation"]["lineNumber"])


if __name__ == "__main__":
    unittest.main()
