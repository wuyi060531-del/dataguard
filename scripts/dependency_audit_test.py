"""Offline checks for fail-closed dependency auditing; no vulnerability service is contacted."""
import importlib.util
from datetime import date
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("audit", Path(__file__).with_name("dependency-audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class DependencyAuditTest(unittest.TestCase):
    def setUp(self):
        self.components = [{"package": {"ecosystem": "Maven", "name": "example:demo"}, "version": "1.2.3"}]
        self.response = {"results": [{"vulns": [{"id": "GHSA-test-1234"}]}]}

    def test_empty_or_truncated_service_response_is_not_clean(self):
        for response in ({}, {"results": []}, {"results": [None]}):
            with self.assertRaises(ValueError):
                audit.audit(self.components, response, [], date(2026, 10, 30))

    def test_pagination_is_incomplete_even_when_first_page_is_empty(self):
        for result in ({"next_page_token": "second-page"},
                       {"vulns": [], "next_page_token": "second-page"}):
            with self.assertRaises(ValueError):
                audit.audit(self.components, {"results": [result]}, [], date(2026, 11, 6))

    def test_error_or_malformed_result_cannot_become_a_clean_scan(self):
        for result in ({"error": "temporary failure"}, {"vulns": None},
                       {"vulns": {}}, {"vulns": [None]}, {"vulns": [{}]}):
            with self.assertRaises(ValueError):
                audit.audit(self.components, {"results": [result]}, [], date(2026, 11, 6))

    def test_empty_successful_result_is_clean_and_scope_is_explicit(self):
        result = audit.audit(self.components, {"results": [{}]}, [], date(2026, 11, 6))
        self.assertEqual("reviewed", result["status"])
        self.assertIn("excludes build plugins", result["coverage"])

    def test_findings_cannot_be_ignored_by_id_alone_or_expired_policy(self):
        policy = [{"id": "GHSA-test-1234", "package": "example:demo", "version": "1.2.2", "expires": "2026-12-31"}]
        self.assertEqual("findings", audit.audit(self.components, self.response, policy, date(2026, 10, 30))["status"])
        policy[0].update(version="1.2.3", expires="2026-10-29")
        self.assertEqual("findings", audit.audit(self.components, self.response, policy, date(2026, 10, 30))["status"])

    def test_documented_exception_is_separate_from_no_findings(self):
        policy = [{"id": "GHSA-test-1234", "package": "example:demo", "version": "1.2.3", "expires": "2026-12-31",
                   "reason": "fixture", "upstream": "https://example.invalid/advisory"}]
        result = audit.audit(self.components, self.response, policy, date(2026, 10, 30))
        self.assertEqual(1, len(result["documentedExceptions"]))
        self.assertEqual([], result["unreviewedFindings"])

    def test_added_feature_invalidates_exception(self):
        policy = [{"id": "GHSA-test-1234", "package": "example:demo", "version": "1.2.3", "expires": "2026-12-31",
                   "reason": "fixture", "upstream": "https://example.invalid/advisory", "mustBeAbsentFromMain": ["class Main"]}]
        with self.assertRaises(ValueError):
            audit.audit(self.components, self.response, policy, date(2026, 10, 30))

    def test_resolved_coordinates_are_deduplicated_and_empty_input_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "dependencies.txt"
            path.write_text("   a.b:c:jar:1.2.3:compile -- module demo\n   a.b:c:jar:1.2.3:test\n")
            self.assertEqual(1, len(audit.components(path)))
            path.write_text("No dependencies were resolved")
            with self.assertRaises(ValueError):
                audit.components(path)


if __name__ == "__main__":
    unittest.main()
