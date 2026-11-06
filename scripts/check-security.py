#!/usr/bin/env python3
"""Offline guard for active Git candidates; not a complete vulnerability/secret audit."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
EXCLUDED = ("archive/", "learning/day01/original/")
PATTERNS = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    "github-token": re.compile(r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})"),
    "aws-access-key": re.compile(r"(?:AKIA|ASIA)[A-Z0-9]{16}"),
    "api-key": re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}"),
    "slack-token": re.compile(r"xox[baprs]-[A-Za-z0-9-]{20,}"),
}
CREDENTIAL_SETTING = re.compile(
    r"(?im)^[ \t]*[\w.-]*(?:password|passwd|api[_-]?key|secret|access[_-]?token)[\w.-]*[ \t]*[:=][ \t]*([^\r\n#]+)"
)


def text_findings(text: str) -> list[tuple[int, str]]:
    # Never print matching values: a CI annotation must not become a second leak.
    return [(text.count("\n", 0, match.start()) + 1, rule)
            for rule, pattern in PATTERNS.items() for match in pattern.finditer(text)]


def configuration_findings(text: str) -> list[tuple[int, str]]:
    findings = []
    for match in CREDENTIAL_SETTING.finditer(text):
        value = match.group(1).strip().strip("\"'")
        if value and not value.startswith(("${", "$ENV{")):
            findings.append((text.count("\n", 0, match.start()) + 1, "literal-credential-setting"))
    return findings


def self_test() -> None:
    probes = ["ghp_" + "A" * 36, "AKIA" + "A" * 16,
              "-----BEGIN " + "PRIVATE KEY-----", "sk-" + "A" * 40]
    assert all(text_findings(probe) for probe in probes)
    assert not text_findings("password=${DATAGUARD_DB_PASSWORD}\nDATAGUARD_DB_PASSWORD=\n")
    assert not text_findings("github_pat_<insert-token-outside-Git>")
    assert configuration_findings("spring.datasource.password=" + "synthetic-example\n")
    assert not configuration_findings("password=${DATAGUARD_DB_PASSWORD}\nDATAGUARD_DB_PASSWORD=\n")
    assert not configuration_findings("DATAGUARD_DB_PASSWORD=\nDATAGUARD_ROOT_PASSWORD=\nPORT=8080\n")
    print("Security guard self-test: seeded token patterns detected; placeholders accepted.")


def candidates() -> list[Path]:
    location = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=ROOT, text=True, capture_output=True)
    if location.returncode or Path(location.stdout.strip()).resolve() != ROOT.resolve():
        raise ValueError("Security guard requires its own Git root; a nested export must not scan its parent repository")
    # Include ordinary, nonignored pending files so the precommit check works before git add.
    names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                    cwd=ROOT).decode("utf-8").split("\0")
    return [Path(name) for name in sorted(set(names)) if name and not name.startswith(EXCLUDED)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0

    failures: list[str] = []
    checked = 0
    try:
        files = candidates()
    except ValueError as failure:
        print(str(failure), file=sys.stderr)
        return 2
    for relative in files:
        path = ROOT / relative
        if not path.exists():
            continue  # A deleted tracked file is not read from an unrelated historical index.
        if path.is_symlink():
            failures.append(f"{relative}: symlink candidates need manual review; not followed")
            continue
        name = path.name.lower()
        if name in {".env", "id_rsa", "id_ed25519", "credentials", "datasources.local.xml"} \
                or (name.startswith(".env.") and name != ".env.example") \
                or path.suffix.lower() in {".pem", ".key", ".p12", ".pfx", ".jks"}:
            failures.append(f"{relative}: sensitive file must stay outside version control")
            continue
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue  # This minimal text check does not claim to scan binary archives.
        checked += 1
        failures.extend(f"{relative}:{line}: possible {rule} (value redacted)"
                        for line, rule in text_findings(text))
        if path.suffix in {".yml", ".yaml", ".properties"} or name.endswith(".env.example"):
            failures.extend(f"{relative}:{line}: possible {rule} (value redacted)"
                            for line, rule in configuration_findings(text))
        if path.suffix in {".yml", ".yaml"} and relative.parts[0] == ".github":
            for match in re.finditer(r"\buses:\s*[\w./-]+@([^\s#]+)", text):
                if not re.fullmatch(r"[0-9a-f]{40}", match.group(1)):
                    failures.append(f"{relative}: third-party actions must use a full 40-character commit SHA")

    pom = ET.parse(ROOT / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for version in pom.findall(".//m:version", ns):
        value = (version.text or "").strip()
        if value and any(marker in value.upper() for marker in ["SNAPSHOT", "LATEST", "RELEASE", "[", "]", "(", ")"]):
            failures.append("pom.xml: floating/range/SNAPSHOT dependency versions need review")

    properties = (ROOT / ".mvn/wrapper/maven-wrapper.properties").read_text()
    if not re.search(r"^distributionSha256Sum=[0-9a-f]{64}$", properties, re.MULTILINE):
        failures.append("Maven Wrapper distribution needs a verified SHA-256 checksum")

    for file in [ROOT / "Dockerfile", ROOT / "compose.yaml"]:
        if file.exists():
            for image in re.findall(r"(?:^FROM\s+|^\s*image:\s*)(\S+)", file.read_text(), re.MULTILINE):
                if image.endswith(":latest") or ":" not in image:
                    failures.append(f"{file.name}: container image needs a fixed version")

    if failures:
        print("Security guard found issues:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Security guard: {checked} active Git/pending text files checked; no known token or pinning violations.")
    print("Scope: ignores archived originals, ignored local files, binaries and Git history; not a full security audit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
