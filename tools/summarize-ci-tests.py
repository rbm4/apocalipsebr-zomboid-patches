"""Render Maven test reports as a GitHub Actions job summary."""

import csv
import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from html import escape
from pathlib import Path
from statistics import median


ROOM_ARMS = ("vanilla", "indexed", "scoped")


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


def print_room_comparison():
    samples = {}
    for arm in ROOM_ARMS:
        path = Path("target/ci") / f"room-registration-{arm}" / "room-registration.csv"
        if path.is_file():
            with path.open(newline="", encoding="utf-8") as source:
                samples[arm] = [row for row in csv.DictReader(source) if row["operation"] == "grow"]

    print("## Room registration A/B\n")
    print("Median editor elapsed time for growth, excluding the first cycle. "
          "The owner move is a single correctness sample.\n")
    print("| Scenario | Vanilla | Indexed lookup | Indexed + scoped |")
    print("| --- | ---: | ---: | ---: |")
    for scenario, label in (("interior", "Interior growth"), ("border", "Cross-boundary growth"),
                            ("move-owner", "Move between owners")):
        values = []
        for arm in ROOM_ARMS:
            times = [int(row["applyNs"]) / 1_000_000 for row in samples.get(arm, ())
                     if row["scenario"] == scenario and (scenario == "move-owner" or row["cycle"] != "0")]
            values.append(f"{median(times):.2f} ms" if times else "Unavailable")
        print("| " + " | ".join([label, *values]) + " |")
    print()


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

    room_comparison = not unit_tests and stage == "room-registration-ab"
    suites = [(label, directory)]
    if room_comparison:
        suites = [(f"room-registration-{arm}", Path("target/ci") / f"room-registration-{arm}" / "failsafe-reports")
                  for arm in ROOM_ARMS]
    details = []
    for suite, reports in suites:
        cases, unreadable = read_cases(reports)
        if cases:
            counts = Counter(case[0] for case in cases)
            total = f"{len(cases)} (partial)" if unreadable else str(len(cases))
            print(f"| {cell(suite)} | {counts['Passed']} | {counts['Failed']} | "
                  f"{counts['Error']} | {counts['Skipped']} | {total} |")
        else:
            print(f"| {cell(suite)} | — | — | — | — | No test results |")
        if cases or unreadable:
            details.append((suite, cases, unreadable))

    print("\nFull reports and logs are in the job's diagnostics artifact.\n")
    if room_comparison:
        print_room_comparison()
    for suite, cases, unreadable in details:
        print_details(suite, cases, unreadable, unit_tests)


if __name__ == "__main__":
    main()
