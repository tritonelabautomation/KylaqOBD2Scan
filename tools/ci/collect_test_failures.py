#!/usr/bin/env python3
"""Build a Markdown digest of a Gradle unit-test run for CI.

Reads the JUnit XML results plus the raw Gradle console log and prints a compact
report to stdout. Used by `.github/workflows/build-apk.yml` to post test failures
as a pull-request comment, because Actions logs and artifacts live on hosts that
are not reachable from every environment that needs to triage them.

Usage:
    python3 tools/ci/collect_test_failures.py [--log PATH] [--results GLOB]

Exit code is always 0; an empty digest means "nothing parseable was found".
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

GRADLE_ERROR = re.compile(
    r"^\s*(e: file://.*|.*error: .*|FAILURE: Build failed with an exception\.|"
    r"> Task .* FAILED|Caused by: .*|What went wrong:.*)$"
)
NOISE = re.compile(
    r"^\s*(Download|Downloading|Resolve|Transforming|Deprecated Gradle)"
)
# "> Task :x" lines are noise unless the task actually failed.
TASK_LINE = re.compile(r"^\s*> Task ")


def parse_junit(results_glob: str):
    """Yield (classname, name, kind, message, first stack frames)."""
    failures = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "files": 0}
    for path in sorted(glob.glob(results_glob, recursive=True)):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            totals["files"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                try:
                    totals[key] += int(suite.get(key, "0") or 0)
                except ValueError:
                    pass
            for case in suite.iter("testcase"):
                for kind in ("failure", "error"):
                    for node in case.findall(kind):
                        message = (node.get("message") or "").strip()
                        body = (node.text or "").strip()
                        frames = [
                            ln.strip()
                            for ln in body.splitlines()
                            if ln.strip().startswith("at ") or ln.strip().startswith("Caused by")
                        ][:4]
                        failures.append(
                            (
                                case.get("classname", "?"),
                                case.get("name", "?"),
                                kind,
                                message or body.splitlines()[0] if body else message,
                                frames,
                            )
                        )
    return failures, totals


def parse_gradle_log(log_path: str, limit: int = 40):
    if not log_path or not os.path.exists(log_path):
        return []
    interesting = []
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.rstrip("\n")
                if NOISE.match(line):
                    continue
                if TASK_LINE.match(line) and "FAILED" not in line:
                    continue
                if GRADLE_ERROR.match(line):
                    interesting.append(line.strip())
    except OSError:
        return []
    # de-duplicate, keep order
    seen, out = set(), []
    for line in interesting:
        if line not in seen:
            seen.add(line)
            out.append(line)
        if len(out) >= limit:
            break
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", default="/tmp/unit-tests.log")
    parser.add_argument(
        "--results",
        default="app/build/test-results/**/*.xml",
    )
    args = parser.parse_args()

    failures, totals = parse_junit(args.results)
    gradle_lines = parse_gradle_log(args.log)

    print("### Unit test report")
    print()
    if totals["files"]:
        print(
            f"`{totals['files']}` suite(s), `{totals['tests']}` test(s): "
            f"**{totals['failures']} failed**, **{totals['errors']} errored**, "
            f"{totals['skipped']} skipped."
        )
    else:
        print("No JUnit XML results were produced — the run most likely failed to compile.")
    print()

    if failures:
        print("#### Failures")
        print()
        for classname, name, kind, message, frames in failures:
            short_class = classname.split(".")[-1]
            print(f"- **`{short_class}.{name}`** ({kind})")
            if message:
                print(f"  - {' '.join(message.split())[:400]}")
            for frame in frames:
                print(f"  - `{frame[:220]}`")
        print()

    if gradle_lines:
        print("#### Gradle output (filtered)")
        print()
        print("```")
        for line in gradle_lines:
            print(line[:400])
        print("```")
        print()

    if not failures and not gradle_lines and not totals["files"]:
        print("_Nothing parseable found; inspect the raw job log._")
    return 0


if __name__ == "__main__":
    sys.exit(main())
