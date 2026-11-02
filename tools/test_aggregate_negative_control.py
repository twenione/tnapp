#!/usr/bin/env python3
"""Fail-closed tests for sharded negative-control aggregation."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
from typing import Any

import aggregate_negative_control as aggregate_tool
import negative_control_engine as engine


def valid_payloads(shards: int = 6) -> list[dict[str, Any]]:
    names = list(engine.VARIANTS)
    exemptions = set(engine.load_structurally_unreachable_mutations(Path.cwd(), set(names)))
    positive_consumers = {
        "core-guide-test", "replay-build", *engine.CONSUMER_NAMES, engine.APP_START_TEST,
    }
    payloads = []
    for index in range(1, shards + 1):
        rows = []
        for name in engine.partition_mutations(names, index, shards):
            required = sorted(engine.MUTATION_REQUIRED_CONSUMERS[name])
            exempt = name in exemptions
            rows.append({
                "mutation": name,
                "required_consumers": required,
                "observed_exit_codes": {consumer: 0 if exempt else 1 for consumer in required},
                "result": "NOT-DETECTABLE (D-179)" if exempt else "DETECTED",
            })
        payloads.append({
            "shard": {"index": index, "count": shards},
            "total_mutation_names": len(names),
            "campaign_passed": True,
            "mutations": rows,
            "summary": aggregate_tool.outcome_summary(rows),
            "positive_control": {
                "passed": True,
                "observed_exit_codes": {consumer: 0 for consumer in positive_consumers},
            },
        })
    return payloads


def write_payloads(directory: Path, payloads: list[dict[str, Any]]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for payload in payloads:
        index = payload["shard"]["index"]
        (directory / f"result-{index}.json").write_text(json.dumps(payload), encoding="utf-8")


def aggregate_payloads(payloads: list[dict[str, Any]]) -> tuple[int, list[str]]:
    with tempfile.TemporaryDirectory(prefix="task085-aggregate-") as temporary:
        directory = Path(temporary)
        write_payloads(directory, payloads)
        return aggregate_tool.aggregate(directory, payloads[0]["shard"]["count"])


def test_valid_114_mutations_emit_sum_ok() -> None:
    code, output = aggregate_payloads(valid_payloads())
    text = "\n".join(output)
    assert code == 0, text
    assert "task080-* | 23 | 2 | 0 | 25" in text
    assert "SUM OK: 114 = 112 detected + 2 exempt + 0 survived" in text


def test_missing_result_file_fails() -> None:
    payloads = valid_payloads()
    payloads.pop()
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("expected exactly" in line or "missing shard indices" in line for line in output)


def test_duplicate_shard_result_fails() -> None:
    payloads = valid_payloads()
    with tempfile.TemporaryDirectory(prefix="task085-duplicate-shard-") as temporary:
        directory = Path(temporary)
        write_payloads(directory, payloads)
        (directory / "duplicate.json").write_text(json.dumps(payloads[0]), encoding="utf-8")
        code, output = aggregate_tool.aggregate(directory, payloads[0]["shard"]["count"])
    assert code == 1
    assert any("expected exactly" in line or "duplicate shard index" in line for line in output)


def test_missing_mutation_fails() -> None:
    payloads = valid_payloads()
    payloads[0]["mutations"].pop()
    payloads[0]["summary"] = aggregate_tool.outcome_summary(payloads[0]["mutations"])
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("mutation union differs" in line or "deterministic partition" in line for line in output)


def test_duplicate_mutation_across_shards_fails() -> None:
    payloads = valid_payloads()
    payloads[1]["mutations"].append(json.loads(json.dumps(payloads[0]["mutations"][0])))
    payloads[1]["summary"] = aggregate_tool.outcome_summary(payloads[1]["mutations"])
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("appears in more than one shard" in line for line in output)


def test_survived_mutation_fails() -> None:
    payloads = valid_payloads()
    payloads[0]["mutations"][0]["result"] = "SURVIVED"
    payloads[0]["summary"] = aggregate_tool.outcome_summary(payloads[0]["mutations"])
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("result is SURVIVED" in line for line in output)


def test_failed_positive_control_fails() -> None:
    payloads = valid_payloads()
    payloads[0]["positive_control"]["passed"] = False
    payloads[0]["campaign_passed"] = False
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("positive control did not pass" in line for line in output)


def test_inconsistent_shard_count_fails() -> None:
    payloads = valid_payloads()
    payloads[1]["shard"]["count"] = 5
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("count=5" in line for line in output)


def test_tampered_summary_fails() -> None:
    payloads = valid_payloads()
    payloads[0]["summary"]["detected"] += 1
    code, output = aggregate_payloads(payloads)
    assert code == 1
    assert any("summary counts do not match" in line for line in output)


def test_workflow_requires_aggregate_job_to_enforce_shard_result() -> None:
    workflow = Path(".github/workflows/guard.yml").read_text(encoding="utf-8")
    assert aggregate_tool.workflow_contract_failures(workflow) == []
    aggregate = aggregate_tool.workflow_job_block(workflow, "negative-control-engine")
    assert aggregate is not None
    broken = workflow.replace(aggregate, aggregate.replace("needs.negative-control-engine-shard.result", "needs.result"))
    assert any("shard job result" in error for error in aggregate_tool.workflow_contract_failures(broken))


def run_all() -> None:
    test_valid_114_mutations_emit_sum_ok()
    test_missing_result_file_fails()
    test_duplicate_shard_result_fails()
    test_missing_mutation_fails()
    test_duplicate_mutation_across_shards_fails()
    test_survived_mutation_fails()
    test_failed_positive_control_fails()
    test_inconsistent_shard_count_fails()
    test_tampered_summary_fails()
    test_workflow_requires_aggregate_job_to_enforce_shard_result()


if __name__ == "__main__":
    run_all()
    print("PASS: aggregate mutation shards, sums, and workflow gating")
