#!/usr/bin/env python3
"""Run D-033 engine mutation fixtures through every real consumer.

The production checkout is never modified.  Each variant is applied to an
isolated temporary copy and the same Gradle/CLI commands used by guard are
run there.  A passing negative-control means every consumer rejected the
mutated engine (non-zero exit), while the command output is retained as the
auditable evidence of the expected failure.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


ENGINE = Path("core-guide/src/main/kotlin/com/trailnav/core/Engine.kt")
VARIANTS = {
    "dwell-bypass": (
        "elapsedSeconds(timestamp, since) >= config.offRouteEnterDwellSeconds",
        "true /* D-033 dwell bypass */",
    ),
    "config-constant": (
        "match.distanceMeters > config.offRouteEnterDistMeters",
        "match.distanceMeters > 25.0 /* D-033 config constant */",
    ),
    "unit-heuristic": (
        "return (now - then) / 1_000.0",
        "val delta = now - then\n    return if (delta >= 1_000L) delta / 1_000.0 else delta.toDouble() /* D-033 unit heuristic */",
    ),
    "turn-consumption": (
        "next.copy(completedTurnAheadIndices = next.completedTurnAheadIndices + index)",
        "next.copy(completedTurnAheadIndices = next.completedTurnAheadIndices) /* D-033 turn consumption removed */",
    ),
    "turn-direction-gate": (
        "if (direction != ProgressDirection.FORWARD || match.distanceMeters >= config.turnOnRouteMaxOffsetMeters)",
        "if (true /* D-033 direction gate mutation */)",
    ),
    "turn-off-route-gate": (
        "if (next.offRoute) {",
        "if (false /* D-033 off-route gate removed */) {",
    ),
    "turn-offset-gate": (
        "if (direction != ProgressDirection.FORWARD || match.distanceMeters >= config.turnOnRouteMaxOffsetMeters)",
        "if (direction != ProgressDirection.FORWARD || false /* D-033 on-route offset ignored */)",
    ),
    "elevation-waypoint-mix": (
        "val sourcePoints = usable.map { it.point }",
        "val sourcePoints = usable.map { it.point } + rawWaypoints.map { it.point } /* D-033 wpt mixed into route */",
    ),
    "elevation-always-ok": (
        "val elevation = classifyElevation(elevations, cumulative, config)",
        "val elevation = classifyElevation(elevations, cumulative, config).copy(used = true, reason = \"ok\") /* D-033 fallback removed */",
    ),
    "waypoint-near-filter": (
        "if (projection.distanceMeters <= config.waypointNearRouteMeters) {",
        "if (true /* D-033 waypoint near-route filter removed */) {",
    ),
    "sunset-drop-pending": (
        "pendingSunsetThresholds = state.pendingSunsetThresholds + newlyCrossed",
        "pendingSunsetThresholds = emptySet() /* D-033 E7 pending dropped */",
    ),
    "sunset-periodic-gate": (
        "if (!config.sunsetEnabled) return SunsetEvaluation(state, null)",
        "if (!config.sunsetEnabled || !config.periodicEnabled) return SunsetEvaluation(state, null) /* D-033 E7 incorrectly gated */",
    ),
    "sunset-no-start": (
        "val firstEvaluation = !previous.sunsetEvaluated",
        "val firstEvaluation = false /* D-033 E7 start announcement removed */",
    ),
    "sunset-epoch-day": (
        "val dayOfYear = localDate.dayOfYear",
        "val dayOfYear = localDate.toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay() /* D-033 seasonal epoch drift */",
    ),
    "sunset-skip-accuracy": (
        "val sunset = evaluateSunsetStandalone(initializedState, frame, config, higherPriority = false)",
        'val sunset = StandaloneSunsetEvaluation(initializedState, null, Reason("event.none")) /* D-033 skip E7 on accuracy frames */',
    ),
    "event-offroute-gate": (
        "evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config, suppressAnnouncements = true).state",
        "evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config, suppressAnnouncements = false).state /* D-033 off-route event gate removed */",
    ),
    "event-reverse-gate": (
        "config.milestoneEnabled && config.periodicEnabled && onRoute && forward && eventIntervalOpen",
        "config.milestoneEnabled && config.periodicEnabled && onRoute && eventIntervalOpen /* D-033 reverse event gate removed */",
    ),
    "event-min-interval": (
        "config.milestoneEnabled && config.periodicEnabled && onRoute && forward && eventIntervalOpen && crossed.isNotEmpty()",
        "config.milestoneEnabled && config.periodicEnabled && onRoute && forward && crossed.isNotEmpty() /* D-033 event interval removed */",
    ),
    "event-consumption-queue": (
        "next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices + crossed)",
        "next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices) /* D-033 E1 consumption removed */",
    ),
    "event-threshold-refire": (
        "index !in previous.consumedSlopeIndices",
        "true /* D-033 consumed E4 threshold refires */",
    ),
    "elevation-fallback": (
        "if (!route.elevationUse.used || route.smoothedElevationMeters.isEmpty())",
        "if (route.smoothedElevationMeters.isEmpty()) /* D-033 E5 fallback ignored */",
    ),
    "priority-old-order": (
        "const val REMAINING = 500",
        "const val REMAINING = 300 /* D-033 old priority order */",
    ),
}


def run(command: list[str], cwd: Path) -> tuple[int, str]:
    result = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", errors="replace", capture_output=True)
    return result.returncode, (result.stdout + "\n" + result.stderr).strip()


def copy_repo(source: Path, destination: Path) -> None:
    ignored = shutil.ignore_patterns(".git", ".gradle", "build", "*.jar", "*.log", ".task005-evidence")
    shutil.copytree(source, destination, ignore=ignored)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--out", type=Path, default=Path("d033-negative-control.log"))
    args = parser.parse_args()
    source = args.repo_root.resolve()
    evidence: list[str] = []
    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="tnapp-d033-") as temporary:
        workspace = Path(temporary) / "repo"
        copy_repo(source, workspace)
        engine = workspace / ENGINE
        original = engine.read_text(encoding="utf-8")
        route = workspace / "core-guide/src/main/kotlin/com/trailnav/core/Route.kt"
        route_original = route.read_text(encoding="utf-8")
        for name, (needle, replacement) in VARIANTS.items():
            target = route if name in {"elevation-waypoint-mix", "elevation-always-ok", "waypoint-near-filter"} else engine
            target_original = route_original if target == route else original
            variant_engine = target_original.replace(needle, replacement, 1)
            if variant_engine == target_original:
                failures.append(f"{name}: mutation needle not found")
                continue
            target.write_text(variant_engine, encoding="utf-8")
            test_code, test_output = run([args.gradle, ":core-guide:test", "--no-daemon"], workspace)
            evidence.append(f"VARIANT {name} consumer=core-guide-test exit={test_code}\n{test_output}")
            if test_code == 0:
                failures.append(f"{name}: core-guide-test unexpectedly passed")

            # A failing test task prevents Gradle from reaching installDist in
            # the same invocation. Build the real CLI separately so each
            # downstream consumer is exercised against the mutated engine.
            cli_code, cli_output = run([args.gradle, ":replay:installDist", "--no-daemon"], workspace)
            evidence.append(f"VARIANT {name} consumer=replay-build exit={cli_code}\n{cli_output}")
            if cli_code != 0:
                failures.append(f"{name}: replay CLI build failed")
            cli = workspace / "replay/build/install/replay/bin/replay"
            if not cli.exists():
                evidence.append(f"VARIANT {name} consumer=replay exit=not-built")
                failures.append(f"{name}: replay CLI was not built")
                failures.append(f"{name}: phase1-accuracy CLI was not built")
                failures.append(f"{name}: config-sensitivity CLI was not built")
                target.write_text(target_original, encoding="utf-8")
                continue
            commands = [("replay", ["python", "tools/replay.py", "--cli", str(cli), "--contract"])]
            route_preprocessing_variants = {"elevation-waypoint-mix", "elevation-always-ok", "waypoint-near-filter"}
            if name not in {"turn-consumption", "turn-direction-gate", *route_preprocessing_variants}:
                commands.append(("config-sensitivity", ["python", "tools/config_sensitivity.py", "--cli", str(cli)]))
            # The Phase 1 acceptance corpus intentionally has no geometric
            # turn scenarios. Turn mutations are therefore exercised by the
            # core-guide tests, replay probe, and config probe; running the
            # Phase 1 score would be a false negative by construction.
            if not name.startswith("turn-") and name not in route_preprocessing_variants:
                commands.insert(1, (
                    "phase1-accuracy",
                    ["python", "tools/phase1_accuracy.py", "--cli", str(cli), "--contract", "--run-id", "d033", "--commit-sha", "fixture"],
                ))
            for consumer, command in commands:
                code, output = run(command, workspace)
                evidence.append(f"VARIANT {name} consumer={consumer} exit={code}\n{output}")
                if code == 0:
                    failures.append(f"{name}: {consumer} unexpectedly passed")
            target.write_text(target_original, encoding="utf-8")

        fixture = workspace / "testdata/sessions/golden/log_shape_fixture"
        if fixture.is_dir():
            broken_fixture = workspace / "d033-replay-missing-loc"
            shutil.copytree(fixture, broken_fixture)
            event_lines = (broken_fixture / "events.ndjson").read_text(encoding="utf-8").splitlines()
            removed = False
            retained: list[str] = []
            for line in event_lines:
                event = json.loads(line)
                if not removed and event.get("stream") == "loc":
                    removed = True
                    continue
                retained.append(line)
            (broken_fixture / "events.ndjson").write_text("\n".join(retained) + "\n", encoding="utf-8")
            pairing_code, pairing_output = run(
                ["python", "tools/replay.py", str(broken_fixture), "--cli", str(cli), "--strict"],
                workspace,
            )
            evidence.append(f"REPLAY missing-loc consumer=replay exit={pairing_code}\n{pairing_output}")
            if pairing_code == 0:
                failures.append("replay: missing-loc mutation unexpectedly passed")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n\n".join(evidence) + "\n", encoding="utf-8")
    if failures:
        print("FAIL: D-033 negative control")
        print("\n".join(failures))
        return 1
    print(f"PASS: D-033 consumers rejected {len(VARIANTS)} engine mutations")
    print(f"evidence={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
