"""Render Maven test reports as a GitHub Actions job summary."""

import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from html import escape
from pathlib import Path


def cell(value):
    text = escape(" ".join(value.split())).replace("|", "&#124;")
    return f"<code>{text}</code>"


def read_cases(directory):
    cases = []
    unreadable = []
    for report in sorted(directory.glob("TEST-*.xml")):
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            unreadable.append(report.name)
            continue
        for test in suite.findall("testcase"):
            if test.find("error") is not None:
                result = "Error"
            elif test.find("failure") is not None:
                result = "Failed"
            elif test.find("skipped") is not None:
                result = "Skipped"
            else:
                result = "Passed"
            cases.append((result, test.get("classname", ""), test.get("name", ""),
                          test.get("time", "—")))
    return cases, unreadable


def print_details(label, cases, unreadable, unit_tests):
    print(f"<details>\n<summary>{escape(label)} ({len(cases)} results)</summary>\n")
    if unreadable:
        print("Unreadable XML reports: " + ", ".join(map(cell, unreadable)) + "\n")
    if cases:
        print("| Result | Test | Time (s) |\n| --- | --- | ---: |")
        status_order = ("Error", "Failed", "Passed", "Skipped")
        for status, class_name, name, seconds in sorted(
            cases, key=lambda case: (status_order.index(case[0]), case[1], case[2])
        ):
            if unit_tests:
                name = f"{class_name.rsplit('.', 1)[-1]}.{name}"
            else:
                name = name.removeprefix("registeredAssertions() ")
            print(f"| {status} | {cell(name)} | {cell(seconds)} |")
    print("\n</details>\n")


def main():
    unit_tests = len(sys.argv) == 1
    if unit_tests:
        label, directory = "Unit tests (Surefire)", Path("target/surefire-reports")
    else:
        stage = sys.argv[1]
        label = "PostgreSQL account bootstrap and SQL verification" if stage == "postgres-bootstrap" else stage
        directory = Path("target/ci") / stage / "failsafe-reports"

    print("## Test results\n")
    outcome = os.environ.get("TEST_OUTCOME", "") or "unavailable"
    print(f"{escape(label)} · **{escape(outcome.capitalize())}**")
    print("\n| Suite or stage | Passed | Failed | Errors | Skipped | Total |")
    print("| --- | ---: | ---: | ---: | ---: | ---: |")

    cases, unreadable = read_cases(directory)
    if cases:
        counts = Counter(case[0] for case in cases)
        total = f"{len(cases)} (partial)" if unreadable else str(len(cases))
        print(f"| {cell(label)} | {counts['Passed']} | {counts['Failed']} | "
              f"{counts['Error']} | {counts['Skipped']} | {total} |")
    else:
        print(f"| {cell(label)} | — | — | — | — | No test results |")

    print("\nFull reports and logs are in the job's diagnostics artifact.\n")
    if cases or unreadable:
        print_details(label, cases, unreadable, unit_tests)


if __name__ == "__main__":
    main()
