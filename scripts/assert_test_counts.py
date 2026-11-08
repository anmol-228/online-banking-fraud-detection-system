#!/usr/bin/env python3
"""Fail the build unless the tests that matter actually RAN.

A green Maven exit code only means "nothing failed". It does not mean the important tests executed:
a test excluded by a tag, skipped by an assumption or never discovered produces no failure at all.
This reads the Surefire XML reports and checks, per class, that tests ran, how many, and that none
were skipped.

Usage:  python scripts/assert_test_counts.py backend/target/surefire-reports [--mysql]

Without --mysql the twelve MySQL-only migration tests are expected to be SKIPPED (there is no MySQL).
With --mysql they must RUN, and the skip count must be zero everywhere.
"""
import glob
import sys
import xml.etree.ElementTree as ET

# class name -> minimum number of tests that must run (not skipped). These are floors, not totals.
MUST_RUN = {
    "AuthorizationFreshnessApiTest": 6,
    "LoginHardeningApiTest": 6,
    "StartupSafetyGuardTest": 11,
    "StartupSafetyRulesTest": 16,
    "FraudRuleSpecificationTest": 5,
    "ConcurrentTransferApiTest": 1,
    "IdempotencyApiTest": 6,
    "MoneyPathIntegrityTest": 5,
    "PoolStarvationApiTest": 1,
    "HoldExpiryApiTest": 5,
    "CustomerCancelApiTest": 10,   # 5 single cases + the race repeated 5 times
    "CustomerResponseApiTest": 6,
    "EvidenceAndEscalationApiTest": 11,
    "NoOutboundDeliveryTest": 3,
}
MYSQL_ONLY = {"SchemaMigrationMysqlTest": 12}
TOTAL_FLOOR = 180  # all tests across both Surefire executions, MySQL-only ones excluded (189 at time of writing)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    directory = sys.argv[1]
    expect_mysql = "--mysql" in sys.argv

    totals = {}
    for path in glob.glob(f"{directory}/TEST-*.xml"):
        with open(path, "rb") as handle:
            data = handle.read()
        # These reports are written by our own build, but an XML parser should never be trusted with
        # a DTD or entity declaration (XXE / entity-expansion). A Surefire report has neither.
        if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
            print(f"refusing to parse {path}: it declares a DTD or entity")
            return 1
        suite = ET.fromstring(data)
        name = suite.get("name", "").split(".")[-1].split("$")[0]
        counts = totals.setdefault(name, {"tests": 0, "skipped": 0, "failures": 0, "errors": 0})
        counts["tests"] += int(suite.get("tests", 0))
        counts["skipped"] += int(suite.get("skipped", 0))
        counts["failures"] += int(suite.get("failures", 0))
        counts["errors"] += int(suite.get("errors", 0))

    problems = []
    required = dict(MUST_RUN)
    if expect_mysql:
        required.update(MYSQL_ONLY)
    for name, floor in sorted(required.items()):
        counts = totals.get(name)
        if counts is None:
            problems.append(f"{name}: no report found (the class did not run at all)")
            continue
        ran = counts["tests"] - counts["skipped"]
        print(f"{name:40s} ran {ran:3d} (floor {floor}), skipped {counts['skipped']}")
        if ran < floor:
            problems.append(f"{name}: only {ran} test(s) ran, expected at least {floor}")
        if counts["failures"] or counts["errors"]:
            problems.append(f"{name}: {counts['failures']} failure(s), {counts['errors']} error(s)")

    if expect_mysql:
        skipped = {n: c["skipped"] for n, c in totals.items() if c["skipped"]}
        if skipped:
            problems.append(f"tests were skipped in a MySQL run: {skipped}")
    else:
        migration = totals.get("SchemaMigrationMysqlTest")
        if migration is not None and migration["tests"] - migration["skipped"] != 0:
            problems.append("MySQL-only tests ran without MySQL (the environment check is wrong)")

    executed = sum(c["tests"] - c["skipped"] for c in totals.values())
    total_floor = TOTAL_FLOOR + (12 if expect_mysql else 0)
    print(f"\n{executed} tests executed in total (floor {total_floor})")
    if executed < total_floor:
        problems.append(f"only {executed} tests executed in total, expected at least {total_floor}")

    if problems:
        print("\nTEST-COUNT GATE FAILED:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("test-count gate: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
