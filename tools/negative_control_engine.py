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
import xml.etree.ElementTree as ET
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
        "if (!config.sunsetEnabled || !config.elapsedEnabled) return SunsetEvaluation(state, null) /* D-033 E7 incorrectly gated */",
    ),
    "sunset-no-start": (
        "val firstEvaluation = !previous.sunsetEvaluated",
        "val firstEvaluation = false /* D-033 E7 start announcement removed */",
    ),
    "sunset-epoch-day": (
        "private fun sunsetEpochSeconds(timestamp: Long, latitude: Double, longitude: Double): Double? {\n"
        "    val instant = Instant.ofEpochMilli(timestamp)\n"
        "    val localDate = instant.atZone(ZoneOffset.UTC).plusSeconds((longitude * 240.0).toLong()).toLocalDate()\n"
        "    // NOAA's fractional year uses the local day-of-year, never days since\n"
        "    // an arbitrary epoch (which introduces a multi-day seasonal drift).\n"
        "    val dayOfYear = localDate.dayOfYear",
        "private fun sunsetEpochSeconds(timestamp: Long, latitude: Double, longitude: Double): Double? {\n"
        "    val instant = Instant.ofEpochMilli(timestamp)\n"
        "    val localDate = instant.atZone(ZoneOffset.UTC).plusSeconds((longitude * 240.0).toLong()).toLocalDate()\n"
        "    // NOAA's fractional year uses the local day-of-year, never days since\n"
        "    // an arbitrary epoch (which introduces a multi-day seasonal drift).\n"
        "    val dayOfYear = localDate.toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay() /* D-033 sunset epoch drift */",
    ),
    "sunset-skip-accuracy": (
        "if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n"
        "        val sunset = evaluateSunsetStandalone(initializedState, frame, config, higherPriority = false)",
        "if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n"
        '        val sunset = StandaloneSunsetEvaluation(initializedState, null, Reason("event.none")) /* D-033 skip E7 on accuracy frames */',
    ),
    "event-offroute-gate": (
        "if (next.offRoute) {\n"
        "        // Advance and consume ordinary event thresholds while off-route, but\n"
        "        // keep E7 pending for a later safety announcement.\n"
        "        next = evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config, suppressAnnouncements = true).state",
        "if (next.offRoute) {\n"
        "        // Advance and consume ordinary event thresholds while off-route, but\n"
        "        // keep E7 pending for a later safety announcement.\n"
        "        next = evaluateDynamicGuidance(initializedState, next.copy(offRoute = false), directedMatch, frame, config, suppressAnnouncements = false).state /* D-033 off-route event gate removed */",
    ),
    "event-reverse-gate": (
        "config.slopeEnabled && onRoute && forward && eventIntervalOpen && index !in previous.consumedSlopeIndices",
        "config.slopeEnabled && onRoute && eventIntervalOpen && index !in previous.consumedSlopeIndices /* D-033 reverse event gate removed */",
    ),
    "event-min-interval": (
        "config.milestoneEnabled && onRoute && forward && eventIntervalOpen && freshCrossed.isNotEmpty()",
        "config.milestoneEnabled && onRoute && forward && freshCrossed.isNotEmpty() /* D-033 event interval removed */",
    ),
    "event-consumption-queue": (
        "next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices + crossed)",
        "next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices) /* D-033 E1 consumption removed */",
    ),
    "event-threshold-refire": (
        "index !in previous.consumedSlopeIndices",
        "true /* D-033 consumed E4 threshold refires */",
    ),
    "reverse-sunset-drop": (
        "if (reverseWarning) {\n"
        "        val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config)\n"
        "        if (dynamic.guidance != null) {",
        "if (reverseWarning) {\n"
        "        val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config)\n"
        "        if (false /* D-033 reverse E7 result dropped */) {",
    ),
    "reverse-events-suppressed": (
        "val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config)\n        if (dynamic.guidance != null)",
        "val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config, suppressAnnouncements = true)\n        if (dynamic.guidance != null) /* D-033 reverse events suppressed */",
    ),
    "reverse-status-repeat": (
        "if (!dynamic.state.reverseStatusIssued) {",
        "if (true /* D-033 reverse status repeats */) {",
    ),
    "elevation-fallback": (
        "if (!route.elevationUse.used || route.smoothedElevationMeters.isEmpty())",
        "if (route.smoothedElevationMeters.isEmpty()) /* D-033 E5 fallback ignored */",
    ),
    "priority-old-order": (
        "const val REMAINING = 500",
        "const val REMAINING = 300 /* D-033 old priority order */",
    ),
    # TASK-038 E5 boundary and E8 sunrise negative controls.
    "elevation-hysteresis-ignore": (
        "while (elevation >= (band + 1) * config.elevationBoundaryMeters + config.elevationHysteresisMeters)",
        "while (elevation >= (band + 1) * config.elevationBoundaryMeters) /* TASK-038 hysteresis ignored */",
    ),
    "elevation-descending-drop": (
        "while (elevation < band * config.elevationBoundaryMeters - config.elevationHysteresisMeters)",
        "while (false /* TASK-038 descending boundary dropped */)",
    ),
    "elevation-start-boundary": (
        "state.copy(elevationBand = floor(elevation / config.elevationBoundaryMeters).toInt())",
        "state.copy(elevationBand = floor(elevation / config.elevationBoundaryMeters).toInt() + 1) /* TASK-038 start boundary off-by-one */",
    ),
    "sunrise-after-start": (
        "minutes <= 0.0 -> config.sunriseAnnounceMinutes.filter { it !in consumed }.toSet()",
        "minutes < -1.0 -> config.sunriseAnnounceMinutes.filter { it !in consumed }.toSet() /* TASK-038 E8 after-start */",
    ),
    "sunrise-zero-minute": (
        "minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||",
        "minutes < -1.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled || /* TASK-038 E8 zero-minute */",
    ),
    "sunrise-consumption-refire": (
        "consumedSunriseThresholds = consumed + newlyCrossed",
        "consumedSunriseThresholds = consumed /* TASK-038 E8 consumption removed */",
    ),
    "sunrise-day-reset": (
        "val dayChanged = state.sunriseLocalDay != localDay",
        "val dayChanged = false /* TASK-038 E8 local-day reset removed */",
    ),
    "sunrise-epoch-day": (
        "internal fun sunriseEpochSeconds(timestamp: Long, latitude: Double, longitude: Double): Double? {\n"
        "    val localDate = sunriseLocalDate(timestamp, longitude)\n"
        "    val dayOfYear = localDate.dayOfYear",
        "internal fun sunriseEpochSeconds(timestamp: Long, latitude: Double, longitude: Double): Double? {\n"
        "    val localDate = sunriseLocalDate(timestamp, longitude)\n"
        "    val dayOfYear = localDate.toEpochDay().toInt() /* TASK-040 E8 epoch-day drift */",
    ),
    "sunrise-min-interval": (
        "!config.sunriseEnabled ||\n        !onRoute || !eventIntervalOpen",
        "!config.sunriseEnabled ||\n        !onRoute /* TASK-038 E8 min interval ignored */",
    ),
    # TASK-040 config sensitivity, event-toggle, priority, and rejected-frame controls.
    "elevation-boundary-config-ignore": (
        "while (elevation >= (band + 1) * config.elevationBoundaryMeters + config.elevationHysteresisMeters) {",
        "while (elevation >= (band + 1) * 100.0 + config.elevationHysteresisMeters) { /* TASK-040 E5 boundary config ignored */",
    ),
    "sunrise-announce-config-ignore": (
        "        else -> config.sunriseAnnounceMinutes.filter {\n            oldMinutes > it && minutes <= it && it !in consumed\n        }.toSet()",
        "        else -> listOf(30, 10).filter {\n            oldMinutes > it && minutes <= it && it !in consumed\n        }.toSet() /* TASK-040 E8 announce config ignored */",
    ),
    "remaining-toggle-ignored": (
        "if (config.remainingEnabled && onRoute && forward && eventIntervalOpen && crossedRemaining.isNotEmpty()) {",
        "if (onRoute && forward && eventIntervalOpen && crossedRemaining.isNotEmpty()) { /* TASK-040 E3 toggle ignored */",
    ),
    "slope-toggle-ignored": (
        "if (config.slopeEnabled && onRoute && forward && eventIntervalOpen && index !in previous.consumedSlopeIndices) {",
        "if (onRoute && forward && eventIntervalOpen && index !in previous.consumedSlopeIndices) { /* TASK-040 E4 toggle ignored */",
    ),
    "waypoint-toggle-ignored": (
        "if (config.waypointEnabled && onRoute && forward && eventIntervalOpen && index !in previous.consumedWaypointIndices) {",
        "if (onRoute && forward && eventIntervalOpen && index !in previous.consumedWaypointIndices) { /* TASK-040 E6 toggle ignored */",
    ),
    "sunset-toggle-ignored": (
        "if (!config.sunsetEnabled) return SunsetEvaluation(state, null)",
        "if (false) return SunsetEvaluation(state, null) /* TASK-040 E7 toggle ignored */",
    ),
    "sunrise-toggle-ignored": (
        "minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||",
        "minutes <= 0.0 || newlyCrossed.isEmpty() || false /* TASK-040 E8 toggle ignored */ ||",
    ),
    "sunrise-reverse-suppressed": (
        "minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||\n        !onRoute || !eventIntervalOpen",
        "minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||\n        !onRoute || !eventIntervalOpen || state.direction == ProgressDirection.REVERSE /* TASK-040 E8 reverse muted */",
    ),
    "priority-elevation-waypoint-reversed": (
        "const val ELEVATION = 250\n    const val WAYPOINT = 300",
        "const val ELEVATION = 300 /* TASK-040 E5 raised */\n    const val WAYPOINT = 200 /* TASK-040 E6 lowered */",
    ),
    "sunrise-rounding-floor": (
        "val minutesRemaining = minutes.roundToInt().coerceAtLeast(1)",
        "val minutesRemaining = minutes.toInt().coerceAtLeast(1) /* TASK-040 E8 rounds down */",
    ),
    "sunset-delayed-by-e5-e8": (
        "val sunsetEvaluation = evaluateSunset(previous, next, frame, config, eventIntervalOpen, candidates.isNotEmpty())",
        "val sunsetEvaluation = evaluateSunset(previous, next, frame, config, eventIntervalOpen, candidates.isNotEmpty() || config.elevationEnabled || config.sunriseEnabled) /* TASK-040 E5/E8 incorrectly delay E7 */",
    ),
    "sunrise-consumes-rejected-accuracy": (
        "    if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n"
        "        val sunset = evaluateSunsetStandalone(initializedState, frame, config, higherPriority = false)\n"
        "        if (sunset.guidance != null) {\n"
        "            return GuideResult(sunset.guidance, sunset.state, sunset.reason.copy(rule = \"input.accuracy-filter\"))\n"
        "        }\n"
        "        return GuideResult(\n"
        "            null,\n"
        "            sunset.state,\n"
        "            Reason(\n"
        "                rule = \"input.accuracy-filter\",\n"
        "                thresholds = mapOf(\"accuracyRejectMeters\" to config.accuracyRejectMeters),\n"
        "                details = mapOf(\"accuracy\" to frame.accuracy.toString())\n"
        "            )\n"
        "        )\n"
        "    }",
        "    if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n"
        "        val sunset = evaluateSunsetStandalone(initializedState, frame, config, higherPriority = false)\n"
        "        val sunriseState = evaluateSunriseStandalone(sunset.state, frame, config) /* TASK-040 E8 rejected frame consumed */\n"
        "        if (sunset.guidance != null) {\n"
        "            return GuideResult(sunset.guidance, sunriseState, sunset.reason.copy(rule = \"input.accuracy-filter\"))\n"
        "        }\n"
        "        return GuideResult(\n"
        "            null,\n"
        "            sunriseState,\n"
        "            Reason(\n"
        "                rule = \"input.accuracy-filter\",\n"
        "                thresholds = mapOf(\"accuracyRejectMeters\" to config.accuracyRejectMeters),\n"
        "                details = mapOf(\"accuracy\" to frame.accuracy.toString())\n"
        "            )\n"
        "        )\n"
        "    }",
    ),
}


CORE_GUIDE_TESTS = {
    "elevation-boundary-config-ignore": ["com.trailnav.core.ElevationBoundaryTest.boundaryConfigChangesCrossings"],
    "elevation-hysteresis-ignore": ["com.trailnav.core.ElevationBoundaryTest.hysteresisConfigChangesChatter"],
    "sunrise-announce-config-ignore": ["com.trailnav.core.SunriseGuidanceTest.announceMinutesConfigChangesCrossings"],
    "remaining-toggle-ignored": ["com.trailnav.core.DynamicGuidanceTest.toggleRemainingOnAndOff"],
    "slope-toggle-ignored": ["com.trailnav.core.DynamicGuidanceTest.toggleSlopeOnAndOff"],
    "waypoint-toggle-ignored": ["com.trailnav.core.DynamicGuidanceTest.toggleWaypointOnAndOff"],
    "sunset-toggle-ignored": ["com.trailnav.core.DynamicGuidanceTest.toggleSunsetOnAndOff"],
    "sunrise-toggle-ignored": ["com.trailnav.core.SunriseGuidanceTest.toggleOffSilentButConsumes"],
    "sunrise-reverse-suppressed": ["com.trailnav.core.SunriseGuidanceTest.sunriseFiresInReverseAfterDwell"],
    "priority-old-order": [
        "com.trailnav.core.EventPriorityTableTest.orderIsSunsetRemainingSunriseSlopeWaypointElevationMilestoneElapsed",
        "com.trailnav.core.EventPriorityTableTest.remainingConsumesSameFrameSunrise",
    ],
    "priority-elevation-waypoint-reversed": [
        "com.trailnav.core.EventPriorityTableTest.orderIsSunsetRemainingSunriseSlopeWaypointElevationMilestoneElapsed",
        "com.trailnav.core.EventPriorityTableTest.waypointWinsElevationCollision",
    ],
    "sunrise-rounding-floor": ["com.trailnav.core.SunriseGuidanceTest.roundsRemainingMinutes"],
    "sunset-delayed-by-e5-e8": [
        "com.trailnav.core.EventPriorityTableTest.sunsetWinsElevationCollisionWithoutPendingDelay",
        "com.trailnav.core.EventPriorityTableTest.sunsetWinsSunriseCollisionWithoutPendingDelay",
    ],
    "sunrise-epoch-day": ["com.trailnav.core.SunriseGuidanceTest.sunriseEpochSecondsMatchesReferenceRows"],
    "sunrise-consumes-rejected-accuracy": ["com.trailnav.core.SunriseGuidanceTest.rejectedAccuracyFrameDefersCrossingToNextGoodFrame"],
}


def run(command: list[str], cwd: Path) -> tuple[int, str]:
    result = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", errors="replace", capture_output=True)
    return result.returncode, (result.stdout + "\n" + result.stderr).strip()


def replace_unique(source: str, needle: str, replacement: str) -> tuple[str, str | None]:
    count = source.count(needle)
    if count != 1:
        return source, f"needle not unique (count={count})"
    return source.replace(needle, replacement), None


def failing_named_tests(workspace: Path, selectors: list[str]) -> set[str]:
    results = workspace / "core-guide/build/test-results/test"
    failed: set[str] = set()
    for xml_path in results.glob("TEST-*.xml"):
        try:
            suite = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        for case in suite.findall("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            class_name = case.attrib.get("classname", "")
            method_name = case.attrib.get("name", "").split("(", 1)[0]
            qualified = f"{class_name}.{method_name}"
            if qualified in selectors:
                failed.add(qualified)
    return failed


def run_named_core_tests(gradle: str, workspace: Path, selectors: list[str]) -> tuple[int, str, set[str]]:
    results = workspace / "core-guide/build/test-results/test"
    shutil.rmtree(results, ignore_errors=True)
    command = [gradle, ":core-guide:test", "--no-daemon"]
    for selector in selectors:
        command.extend(["--tests", selector])
    code, output = run(command, workspace)
    return code, output, failing_named_tests(workspace, selectors)


def copy_repo(source: Path, destination: Path) -> None:
    ignored = shutil.ignore_patterns(".git", ".gradle", "build", "*.jar", "*.log", ".task005-evidence")
    shutil.copytree(source, destination, ignore=ignored)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--out", type=Path, default=Path("d033-negative-control.log"))
    parser.add_argument("--only", action="append", choices=sorted(VARIANTS), help="run only the named mutation; repeat as needed")
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
        cli: Path | None = None
        duplicate_result, duplicate_error = replace_unique("needle\nneedle\n", "needle", "changed")
        if duplicate_result != "needle\nneedle\n" or duplicate_error != "needle not unique (count=2)":
            failures.append("needle-uniqueness-self-test: duplicate needle was not rejected")
        duplicate_unchanged = duplicate_result == "needle\nneedle\n"
        evidence.append(
            "NEEDLE-UNIQUENESS self-test expected=needle not unique (count=2) "
            f"actual={duplicate_error or 'accepted'} source-unchanged={duplicate_unchanged}"
        )
        for name, (needle, replacement) in VARIANTS.items():
            if args.only and name not in args.only:
                continue
            target = route if name in {"elevation-waypoint-mix", "elevation-always-ok", "waypoint-near-filter"} else engine
            target_original = route_original if target == route else original
            variant_engine, needle_error = replace_unique(target_original, needle, replacement)
            if needle_error is not None:
                evidence.append(f"VARIANT {name} mutation=not-applied {needle_error}")
                failures.append(f"{name}: {needle_error}")
                continue
            target.write_text(variant_engine, encoding="utf-8")
            if name in CORE_GUIDE_TESTS:
                selectors = CORE_GUIDE_TESTS[name]
                test_code, test_output, named_failures = run_named_core_tests(args.gradle, workspace, selectors)
                evidence.append(
                    f"VARIANT {name} consumer=core-guide-test exit={test_code} "
                    f"intended_failures={','.join(sorted(named_failures)) or 'none'}\n{test_output}"
                )
                if named_failures != set(selectors):
                    missing = sorted(set(selectors) - named_failures)
                    failures.append(f"{name}: intended core-guide tests did not fail: {', '.join(missing)}")
                target.write_text(target_original, encoding="utf-8")
                continue
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
            sunset_variants = {
                "sunset-drop-pending", "sunset-periodic-gate", "sunset-no-start",
                "sunset-epoch-day", "sunset-skip-accuracy", "reverse-sunset-drop",
            }
            event_variants = {
                "event-offroute-gate", "event-reverse-gate", "event-min-interval",
                "event-consumption-queue", "event-threshold-refire", "elevation-fallback",
                "priority-old-order", "reverse-events-suppressed", "reverse-status-repeat",
                "elevation-hysteresis-ignore", "elevation-descending-drop", "elevation-start-boundary",
                "sunrise-after-start", "sunrise-zero-minute",
                "sunrise-consumption-refire", "sunrise-day-reset", "sunrise-epoch-day",
                "sunrise-min-interval",
                "elevation-boundary-config-ignore", "sunrise-announce-config-ignore",
                "remaining-toggle-ignored", "slope-toggle-ignored", "waypoint-toggle-ignored",
                "sunset-toggle-ignored", "sunrise-toggle-ignored", "sunrise-reverse-suppressed",
                "priority-elevation-waypoint-reversed", "sunrise-rounding-floor",
                "sunset-delayed-by-e5-e8", "sunrise-consumes-rejected-accuracy",
            }
            if name not in {"turn-consumption", "turn-direction-gate", *route_preprocessing_variants, *sunset_variants, *event_variants}:
                commands.append(("config-sensitivity", ["python", "tools/config_sensitivity.py", "--cli", str(cli)]))
            # The Phase 1 acceptance corpus intentionally has no geometric
            # turn scenarios. Turn mutations are therefore exercised by the
            # core-guide tests, replay probe, and config probe; running the
            # Phase 1 score would be a false negative by construction.
            if not name.startswith("turn-") and name not in { *route_preprocessing_variants, *sunset_variants, *event_variants }:
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
        if cli is not None and fixture.is_dir():
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
    print(f"PASS: D-033 consumers rejected {len(args.only or VARIANTS)} engine mutations")
    print(f"evidence={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
