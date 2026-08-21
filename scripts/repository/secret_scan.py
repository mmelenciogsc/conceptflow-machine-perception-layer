#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Scan tracked-like text for high-confidence credentials and key material."""

from __future__ import annotations

import re
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
SELF = Path(__file__).resolve().relative_to(ROOT)
HIGH_CONFIDENCE = {
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "GitHub token": re.compile(r"\bgh(?:p|o|u|s|r)_[A-Za-z0-9]{30,255}\b"),
    "Google API key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "Slack token": re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,}\b"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
}
SENSITIVE_ASSIGNMENT = re.compile(
    r"(?i)\b(api[_-]?key|client[_-]?secret|password|private[_-]?key|secret|token)\b"
    r"\s*[:=]\s*[\"']([^\"'\s]{8,})[\"']"
)
SAFE_MARKERS = ("example", "fake", "redacted", "sample", "synthetic", "test-only", "test_value")
SENSITIVE_FILENAMES = re.compile(r"(?i)(^|/)(\.env|id_rsa|id_ed25519|credentials?\.(json|ya?ml)|secrets?\.)")


def repository_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return sorted(Path(item.decode()) for item in result.stdout.split(b"\0") if item)


def main() -> int:
    findings: list[str] = []
    scanned = 0
    for path in repository_files():
        path_string = path.as_posix()
        if path == SELF:
            continue
        if SENSITIVE_FILENAMES.search(path_string) and not path_string.endswith(".env.example"):
            findings.append(f"sensitive filename: {path_string}")
        absolute = ROOT / path
        if not absolute.is_file() or absolute.stat().st_size > 2_000_000:
            continue
        try:
            text = absolute.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        scanned += 1
        for label, pattern in HIGH_CONFIDENCE.items():
            if pattern.search(text):
                findings.append(f"{label} pattern: {path_string}")
        if "tests" not in path.parts and "src/test" not in path_string:
            for match in SENSITIVE_ASSIGNMENT.finditer(text):
                value = match.group(2).casefold()
                if not any(marker in value for marker in SAFE_MARKERS):
                    line = text.count("\n", 0, match.start()) + 1
                    findings.append(f"credential-like assignment: {path_string}:{line}")

    if findings:
        for finding in findings:
            print(f"secret scan error: {finding}", file=sys.stderr)
        return 1
    print(f"secret scan passed for {scanned} text files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
