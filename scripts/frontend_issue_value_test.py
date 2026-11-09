#!/usr/bin/env python3
"""Runs the Node pure-function tests for the front end (no browser, no backend, no network)."""
import shutil
import subprocess
import unittest
from pathlib import Path


class IssueValueFrontendTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "Node.js is required; GitHub-hosted runners provide it")
    def test_issue_value_node_tests_pass(self):
        self._run("issue-value.test.cjs")

    @unittest.skipUnless(shutil.which("node"), "Node.js is required; GitHub-hosted runners provide it")
    def test_rule_click_reveal_node_tests_pass(self):
        self._run("rule-click-reveal.test.cjs")

    def _run(self, name):
        script = Path(__file__).with_name(name)
        result = subprocess.run(["node", "--test", str(script)], capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
