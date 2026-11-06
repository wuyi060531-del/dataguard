#!/usr/bin/env python3
"""Query public Maven coordinates in OSV; network failure is an error, never a clean audit."""
from __future__ import annotations

import argparse
from datetime import date, datetime, timezone
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def components(path: Path) -> list[dict]:
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.strip().split(" -- ")[0].split(":")
        if len(fields) in (5, 6) and fields[-1] in ("compile", "runtime", "provided", "test", "system"):
            name, version = fields[0] + ":" + fields[1], fields[-2]
            rows[(name, version)] = {"package": {"ecosystem": "Maven", "name": name}, "version": version}
    if not rows:
        raise ValueError("No resolved Maven components; first run dependency:list with an output file")
    return list(rows.values())


def audit(rows: list[dict], response: dict, policy: list[dict], today: date) -> dict:
    if not isinstance(response, dict) or set(response) - {"results"}:
        raise ValueError("Unexpected OSV response envelope; audit incomplete")
    results = response.get("results")
    if not isinstance(results, list) or len(results) != len(rows) or any(not isinstance(x, dict) for x in results):
        raise ValueError("OSV response count/shape does not match resolved dependencies")
    findings, exceptions = [], []
    for component, result in zip(rows, results):
        # querybatch can paginate individual results. Never report a partial page as a clean scan.
        # Current bounded CLI deliberately stops rather than silently expanding network work.
        if set(result) - {"vulns", "next_page_token"} or result.get("next_page_token"):
            raise ValueError("Incomplete/paginated OSV result; explicitly fetch all pages before auditing")
        vulnerabilities = result.get("vulns", [])
        if not isinstance(vulnerabilities, list) or any(not isinstance(v, dict) for v in vulnerabilities):
            raise ValueError("Invalid OSV vulnerability list")
        for vulnerability in vulnerabilities:
            identifier = vulnerability.get("id", "")
            if not re.fullmatch(r"[A-Za-z0-9-]+", identifier):
                raise ValueError("OSV returned an invalid advisory identifier")
            item = {"package": component["package"]["name"], "version": component["version"],
                    "id": identifier, "url": "https://osv.dev/vulnerability/" + identifier}
            accepted = next((p for p in policy if all(p.get(k) == item[k] for k in ("package", "version", "id"))
                             and date.fromisoformat(p["expires"]) >= today), None)
            if accepted:
                guards = accepted.get("mustBeAbsentFromMain", [])
                for source in (ROOT / "src/main").rglob("*"):
                    if source.is_file() and source.suffix in (".java", ".properties", ".xml"):
                        content = source.read_text(encoding="utf-8")
                        if any(token in content for token in guards):
                            raise ValueError("A security exception prerequisite changed; review the documented feature reachability")
                exceptions.append({**item, "reason": accepted["reason"], "expires": accepted["expires"],
                                   "upstream": accepted["upstream"]})
            else:
                findings.append(item)
    return {"queriedComponents": len(rows), "coverage": "Resolved Maven dependencies, including test scope; excludes build plugins and container OS",
            "unreviewedFindings": findings,
            "documentedExceptions": exceptions, "status": "findings" if findings else "reviewed"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=ROOT / "config/security-exceptions.json")
    parser.add_argument("--proxy", help="Optional process-only HTTPS proxy; never written to output")
    args = parser.parse_args()
    report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "source": "https://api.osv.dev/v1/querybatch"}
    try:
        rows = components(args.dependencies)
        if len(rows) > 200:
            raise ValueError("Audit limited to 200 components; split the request explicitly before expanding")
        handlers = [urllib.request.ProxyHandler({"https": args.proxy})] if args.proxy else []
        request = urllib.request.Request(report["source"], data=json.dumps({"queries": rows}).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.build_opener(*handlers).open(request, timeout=30) as response:
            payload = response.read(16 * 1024 * 1024 + 1)
        if len(payload) > 16 * 1024 * 1024:
            raise ValueError("OSV response exceeds audit budget")
        policy = json.loads(args.policy.read_text(encoding="utf-8"))
        report.update(audit(rows, json.loads(payload), policy, date.today()))
        result = 1 if report["unreviewedFindings"] else 0
    except (OSError, ValueError, KeyError, TypeError) as failure:
        report.update(status="error", error=type(failure).__name__,
                      message="Dependency audit incomplete; check input, OSV connectivity and exception prerequisites")
        result = 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OSV audit: {report['status']}; components={report.get('queriedComponents', '?')}; "
          f"unreviewed={len(report.get('unreviewedFindings', []))}; documented={len(report.get('documentedExceptions', []))}")
    return result


if __name__ == "__main__":
    sys.exit(main())
