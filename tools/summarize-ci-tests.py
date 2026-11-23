"""Render Maven test reports as a GitHub Actions job summary."""

import os
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
    ci = Path("target/ci")
    postgres = ci / "postgres-bootstrap"
    stages = sorted(log.parent for log in ci.glob("*/maven.log") if log.parent != postgres)
    reports = [
        ("Unit tests (Surefire)", Path("target/surefire-reports")),
        *((stage.name, stage / "failsafe-reports") for stage in stages),
        ("PostgreSQL account bootstrap (Failsafe)", postgres / "failsafe-reports"),
    ]

    print("## Test results\n")
    for label, variable in (
        ("Stable original-JAR suite", "STABLE_SUITE_OUTCOME"),
        ("PostgreSQL account bootstrap and SQL verification", "POSTGRES_BOOTSTRAP_OUTCOME"),
    ):
        outcome = os.environ.get(variable, "").capitalize() or "Unknown"
        print(f"- {label} — **{escape(outcome)}**")

    print("\n| Suite or stage | Passed | Failed | Errors | Skipped | Total |")
    print("| --- | ---: | ---: | ---: | ---: | ---: |")
    details = []
    for index, (label, directory) in enumerate(reports):
        cases, unreadable = read_cases(directory)
        if cases:
            counts = Counter(case[0] for case in cases)
            total = f"{len(cases)} (partial)" if unreadable else str(len(cases))
            print(f"| {cell(label)} | {counts['Passed']} | {counts['Failed']} | "
                  f"{counts['Error']} | {counts['Skipped']} | {total} |")
        else:
            print(f"| {cell(label)} | — | — | — | — | No test results |")
        if cases or unreadable:
            details.append((label, cases, unreadable, index == 0))

    print("\nThe stable sequence stops at the first failing stage. "
          "Full reports and logs are in the integration diagnostics artifact.\n")
    for detail in details:
        print_details(*detail)


if __name__ == "__main__":
    main()
