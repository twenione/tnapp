#!/usr/bin/env python3
"""Validate and summarize all negative-control mutation shard results."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

import negative_control_engine as engine


RESULTS = ("DETECTED", "NOT-DETECTABLE (D-179)", "SURVIVED", "INVALID")


def workflow_job_block(workflow: str, job_name: str) -> str | None:
    lines = workflow.splitlines()
    start = next((index for index, line in enumerate(lines) if line == f"  {job_name}:"), None)
    if start is None:
        return None
    end = next(
        (index for index in range(start + 1, len(lines)) if re.fullmatch(r"  [A-Za-z0-9_-]+:", lines[index])),
        len(lines),
    )
    return "\n".join(lines[start:end])


def workflow_contract_failures(workflow: str) -> list[str]:
    failures: list[str] = []
    shard = workflow_job_block(workflow, "negative-control-engine-shard")
    aggregate = workflow_job_block(workflow, "negative-control-engine")
    if shard is None:
        failures.append("missing negative-control-engine-shard job")
    if aggregate is None:
        failures.append("missing negative-control-engine aggregate job")
    if shard is not None:
        if "fail-fast: false" not in shard:
            failures.append("shard job must set fail-fast: false")
        if "shard:" not in shard or "1, 2, 3, 4, 5, 6" not in shard:
            failures.append("shard job must define its matrix indexes")
        if "--shard ${{ matrix.shard }}/6" not in shard:
            failures.append("shard job must pass the matrix index and configured shard count")
        if "--out d033-negative-control-${{ matrix.shard }}.log" not in shard:
            failures.append("each shard must use a distinct log path")
        if "--result-json d033-result-${{ matrix.shard }}.json" not in shard:
            failures.append("each shard must use a distinct result JSON path")
    if aggregate is not None:
        if not re.search(r"^    needs:\s*\[?\s*negative-control-engine-shard", aggregate, re.MULTILINE):
            failures.append("aggregate job must need negative-control-engine-shard")
        if not re.search(r"^    if:\s*always\(\)(?:\s*&&\s*\(.+\))?\s*$", aggregate, re.MULTILINE):
            failures.append("aggregate job must retain always() for enabled runs")
        if "needs.negative-control-engine-shard.result" not in aggregate:
            failures.append("aggregate job must fail when the shard job result is not success")
    return failures


def outcome_summary(mutations: list[dict[str, Any]]) -> dict[str, int]:
    results = [row.get("result") for row in mutations]
    return {
        "detected": results.count("DETECTED"),
        "not_detectable": results.count("NOT-DETECTABLE (D-179)"),
        "survived": results.count("SURVIVED"),
        "invalid": results.count("INVALID"),
        "total": len(results),
    }


def aggregate(directory: Path, shard_count: int) -> tuple[int, list[str]]:
    failures: list[str] = []
    if shard_count < 1:
        return 1, ["FAIL: shard count must be positive"]
    expected_names = set(engine.MUTATION_REQUIRED_CONSUMERS)
    if expected_names != set(engine.VARIANTS):
        return 1, ["FAIL: mutation names and required-consumer table differ"]
    try:
        exempt_names = set(engine.load_structurally_unreachable_mutations(Path.cwd(), expected_names))
    except ValueError as exc:
        return 1, [f"FAIL: cannot load D-179 exemptions: {exc}"]

    result_paths = sorted(directory.glob("*.json")) if directory.is_dir() else []
    if len(result_paths) != shard_count:
        failures.append(f"FAIL: expected exactly {shard_count} shard JSON files, found {len(result_paths)}")

    by_index: dict[int, dict[str, Any]] = {}
    all_mutations: dict[str, dict[str, Any]] = {}
    expected_positive_consumers = {
        "core-guide-test", "replay-build", *engine.CONSUMER_NAMES, engine.APP_START_TEST,
    }
    for path in result_paths:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            failures.append(f"FAIL: {path.name}: cannot read JSON: {exc}")
            continue
        if not isinstance(data, dict):
            failures.append(f"FAIL: {path.name}: result must be a JSON object")
            continue
        shard = data.get("shard")
        if not isinstance(shard, dict):
            failures.append(f"FAIL: {path.name}: missing shard object")
            continue
        index = shard.get("index")
        count = shard.get("count")
        if type(index) is not int or type(count) is not int:
            failures.append(f"FAIL: {path.name}: shard index/count must be integers")
            continue
        if count != shard_count:
            failures.append(f"FAIL: {path.name}: count={count}, expected {shard_count}")
        if not 1 <= index <= shard_count:
            failures.append(f"FAIL: {path.name}: index={index} outside 1..{shard_count}")
        elif index in by_index:
            failures.append(f"FAIL: duplicate shard index {index}: {by_index[index]['_path']} and {path.name}")
        else:
            data["_path"] = path.name
            by_index[index] = data
        if data.get("total_mutation_names") != len(engine.VARIANTS):
            failures.append(
                f"FAIL: {path.name}: total_mutation_names={data.get('total_mutation_names')!r}, "
                f"expected {len(engine.VARIANTS)}"
            )
        if data.get("campaign_passed") is not True:
            failures.append(f"FAIL: {path.name}: campaign_passed is not true")

        positive = data.get("positive_control")
        if not isinstance(positive, dict) or positive.get("passed") is not True:
            failures.append(f"FAIL: {path.name}: unmutated positive control did not pass")
        else:
            codes = positive.get("observed_exit_codes")
            if not isinstance(codes, dict) or set(codes) != expected_positive_consumers:
                failures.append(f"FAIL: {path.name}: positive control consumer set is incomplete or unexpected")
            elif any(type(code) is not int or code != 0 for code in codes.values()):
                failures.append(f"FAIL: {path.name}: positive control has a nonzero or invalid exit code")

        mutations = data.get("mutations")
        if not isinstance(mutations, list):
            failures.append(f"FAIL: {path.name}: mutations must be a list")
            mutations = []
        if data.get("summary") != outcome_summary(mutations):
            failures.append(f"FAIL: {path.name}: summary counts do not match mutation rows")
        expected_for_shard = set(engine.partition_mutations(list(engine.VARIANTS), index, shard_count)) if 1 <= index <= shard_count else set()
        shard_names: set[str] = set()
        for row_number, row in enumerate(mutations, start=1):
            if not isinstance(row, dict):
                failures.append(f"FAIL: {path.name}: mutation row {row_number} is not an object")
                continue
            name = row.get("mutation")
            if not isinstance(name, str) or name not in expected_names:
                failures.append(f"FAIL: {path.name}: unknown mutation name {name!r}")
                continue
            if name in shard_names:
                failures.append(f"FAIL: {path.name}: mutation {name} is duplicated within a shard")
            shard_names.add(name)
            if name in all_mutations:
                failures.append(f"FAIL: mutation {name} appears in more than one shard")
            else:
                all_mutations[name] = row
            required = row.get("required_consumers")
            expected_required = sorted(engine.MUTATION_REQUIRED_CONSUMERS[name])
            if required != expected_required:
                failures.append(f"FAIL: {path.name}: {name} required consumers differ from the canonical table")
            codes = row.get("observed_exit_codes")
            if not isinstance(codes, dict) or any(
                consumer not in codes or type(codes.get(consumer)) is not int
                for consumer in expected_required
            ):
                failures.append(f"FAIL: {path.name}: {name} required consumer exit codes are missing or invalid")
                codes = {}
            result = row.get("result")
            if result not in RESULTS:
                failures.append(f"FAIL: {path.name}: {name} has invalid result {result!r}")
            elif result in {"SURVIVED", "INVALID"}:
                failures.append(f"FAIL: {path.name}: {name} result is {result}")
            elif name in exempt_names:
                if result != "NOT-DETECTABLE (D-179)":
                    failures.append(f"FAIL: {path.name}: exempt mutation {name} was not reported as D-179")
                if any(codes.get(consumer) != 0 for consumer in expected_required):
                    failures.append(f"FAIL: {path.name}: exempt mutation {name} has a failed consumer")
            else:
                if result != "DETECTED":
                    failures.append(f"FAIL: {path.name}: non-exempt mutation {name} was not detected")
                if any(codes.get(consumer) == 0 for consumer in expected_required):
                    failures.append(f"FAIL: {path.name}: detected mutation {name} has a passing required consumer")
        if shard_names != expected_for_shard:
            failures.append(
                f"FAIL: {path.name}: shard mutation names differ from deterministic partition; "
                f"missing={sorted(expected_for_shard - shard_names)} extra={sorted(shard_names - expected_for_shard)}"
            )

    missing_indices = sorted(set(range(1, shard_count + 1)) - set(by_index))
    if missing_indices:
        failures.append(f"FAIL: missing shard indices {missing_indices}")
    if set(all_mutations) != expected_names:
        failures.append(
            f"FAIL: mutation union differs from required table; "
            f"missing={sorted(expected_names - set(all_mutations))} "
            f"extra={sorted(set(all_mutations) - expected_names)}"
        )
    if failures:
        return 1, failures

    rows = list(all_mutations.items())
    counts = outcome_summary([row for _, row in rows])
    group_lines: list[str] = [
        "group | detected | not-detectable (D-179) | survived | sum",
        "--- | ---: | ---: | ---: | ---:",
    ]
    for label, predicate in (
        ("task080-*", lambda name: name.startswith("task080-")),
        ("other", lambda name: not name.startswith("task080-")),
    ):
        group_rows = [row for name, row in rows if predicate(name)]
        group_counts = outcome_summary(group_rows)
        recognized = group_counts["detected"] + group_counts["not_detectable"] + group_counts["survived"]
        if recognized != group_counts["total"] or group_counts["invalid"]:
            return 1, [f"FAIL: {label} outcome sum does not match group total"]
        group_lines.append(
            f"{label} | {group_counts['detected']} | {group_counts['not_detectable']} | "
            f"{group_counts['survived']} | {group_counts['total']}"
        )
    recognized_total = counts["detected"] + counts["not_detectable"] + counts["survived"]
    if recognized_total != len(expected_names) or counts["total"] != len(expected_names) or counts["invalid"]:
        return 1, [f"FAIL: overall outcome sum {recognized_total} does not match {len(expected_names)} mutations"]
    group_lines.append(
        f"SUM OK: {recognized_total} = {counts['detected']} detected + "
        f"{counts['not_detectable']} exempt + {counts['survived']} survived"
    )
    return 0, group_lines


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", type=Path, required=True, help="directory containing one JSON per shard")
    parser.add_argument("--shards", type=int, required=True)
    args = parser.parse_args()
    code, lines = aggregate(args.dir, args.shards)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
