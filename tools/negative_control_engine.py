#!/usr/bin/env python3
"""Run D-033 engine mutation fixtures through every real consumer.

The production checkout is never modified.  Each variant is applied to an
isolated temporary copy and the same Gradle/CLI commands used by guard are
run there.  A passing negative-control means every consumer named for that
mutation rejected it, while other consumer outcomes remain in the evidence
log for review.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from xml.etree import ElementTree as ET


ENGINE = Path("core-guide/src/main/kotlin/com/trailnav/core/Engine.kt")
VARIANTS = {'dwell-bypass': ('elapsedSeconds(timestamp, since) >= config.offRouteEnterDwellSeconds',
                  'true /* D-033 dwell bypass */'),
 'approach-gate-removed': ('if (!next.hasEnteredRoute) {',
                           'if (false /* D-033 approach gate removed */) {'),
 'config-constant': ('match.distanceMeters > config.offRouteEnterDistMeters',
                     'match.distanceMeters > 25.0 /* D-033 config constant */'),
 'unit-heuristic': ('return (now - then) / 1_000.0',
                    'val delta = now - then\n'
                    '    return if (delta >= 1_000L) delta / 1_000.0 else delta.toDouble() /* D-033 unit '
                    'heuristic */'),
 'turn-merge-gap-ignored': ('val suppressAhead = index > 0 &&\n'
                            '                turn.s - state.route.turns[index - 1].s < config.turnMergeGapMeters',
                            'val suppressAhead = false /* D-033 turn merge gap ignored */'),
 'turn-consumption': ('next.copy(completedTurnAheadIndices = next.completedTurnAheadIndices + index)',
                      'next.copy(completedTurnAheadIndices = next.completedTurnAheadIndices) /* D-033 turn '
                      'consumption removed */'),
 'turn-direction-gate': ('if (direction != ProgressDirection.FORWARD || match.distanceMeters >= '
                         'config.turnOnRouteMaxOffsetMeters)',
                         'if (true /* D-033 direction gate mutation */)'),
 'turn-off-route-gate': ('if (next.offRoute) {', 'if (false /* D-033 off-route gate removed */) {'),
 'turn-offset-gate': ('if (direction != ProgressDirection.FORWARD || match.distanceMeters >= '
                      'config.turnOnRouteMaxOffsetMeters)',
                      'if (direction != ProgressDirection.FORWARD || false /* D-033 on-route offset ignored '
                      '*/)'),
 'peak-prominence-ignored': ('if (peakElevation - elevation >= config.peakProminenceMeters)',
                             'if (true /* D-033 peak prominence ignored */)'),
 'elevation-waypoint-mix': ('val sourcePoints = usable.map { it.point }',
                            'val sourcePoints = usable.map { it.point } + rawWaypoints.map { it.point } /* '
                            'D-033 wpt mixed into route */'),
 'elevation-always-ok': ('val elevation = classifyElevation(elevations, cumulative, config)',
                         'val elevation = classifyElevation(elevations, cumulative, config).copy(used = '
                         'true, reason = "ok") /* D-033 fallback removed */'),
 'turn-axis-simplified': ('val positionOnOriginalAxis = originalCumulative[originalPointIndices[index]]',
                          'val positionOnOriginalAxis = cumulative[index] /* D-033 simplified turn axis */'),
 'waypoint-near-filter': ('if (projection.distanceMeters <= config.waypointNearRouteMeters) {',
                          'if (true /* D-033 waypoint near-route filter removed */) {'),
 'sunset-drop-pending': ('pendingSunsetThresholds = state.pendingSunsetThresholds + newlyCrossed',
                         'pendingSunsetThresholds = emptySet() /* D-033 E7 pending dropped */'),
 'sunset-periodic-gate': ('if (!config.sunsetEnabled) return SunsetEvaluation(state, null)',
                          'if (!config.sunsetEnabled || !config.elapsedEnabled) return '
                          'SunsetEvaluation(state, null) /* D-033 E7 incorrectly gated */'),
 'sunset-no-start': ('val firstEvaluation = !previous.sunsetEvaluated',
                     'val firstEvaluation = false /* D-033 E7 start announcement removed */'),
 'sunset-epoch-day': ("// NOAA's fractional year uses the local day-of-year, never days since\n"
                      '    // an arbitrary epoch (which introduces a multi-day seasonal drift).\n'
                      '    val dayOfYear = localDate.dayOfYear',
                      "// NOAA's fractional year uses the local day-of-year, never days since\n"
                      '    // an arbitrary epoch (which introduces a multi-day seasonal drift).\n'
                      '    val dayOfYear = localDate.toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay() /* '
                      'D-033 seasonal epoch drift */'),
 'sunset-skip-accuracy': ('if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n'
                          '        val sunset = evaluateSunsetStandalone(initializedState, frame, config, '
                          'higherPriority = false)',
                          'if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n'
                          '        val sunset = StandaloneSunsetEvaluation(initializedState, null, '
                          'Reason("event.none")) /* D-033 skip E7 on accuracy frames */'),
 'event-offroute-gate': ('if (next.offRoute) {\n'
                         '        // Advance and consume ordinary event thresholds while off-route, but\n'
                         '        // keep E7 pending for a later safety announcement.\n'
                         '        next = evaluateDynamicGuidance(initializedState, next, directedMatch, '
                         'frame, config, suppressAnnouncements = true).state',
                         'if (next.offRoute) {\n'
                         '        // Advance and consume ordinary event thresholds while off-route, but\n'
                         '        // keep E7 pending for a later safety announcement.\n'
                         '        next = evaluateDynamicGuidance(initializedState, next.copy(offRoute = '
                         'false), directedMatch, frame, config, suppressAnnouncements = false).state /* '
                         'D-033 off-route event gate removed */'),
 'event-reverse-gate': ('config.slopeEnabled && onRoute && forward && eventIntervalOpen && index !in '
                        'previous.consumedSlopeIndices',
                        'config.slopeEnabled && onRoute && eventIntervalOpen && index !in '
                        'previous.consumedSlopeIndices /* D-033 reverse event gate removed */'),
 'event-min-interval': ('config.milestoneEnabled && onRoute && forward && eventIntervalOpen && '
                        'freshCrossed.isNotEmpty()',
                        'config.milestoneEnabled && onRoute && forward && freshCrossed.isNotEmpty() /* D-033 '
                        'event interval removed */'),
 'event-consumption-queue': ('next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices + '
                             'crossed)',
                             'next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices) /* '
                             'D-033 E1 consumption removed */'),
 'event-threshold-refire': ('index !in previous.consumedSlopeIndices',
                            'true /* D-033 consumed E4 threshold refires */'),
 'reverse-sunset-drop': ('if (reverseWarning) {\n'
                         '        val dynamic = evaluateDynamicGuidance(initializedState, next, '
                         'directedMatch, frame, config)\n'
                         '        if (dynamic.guidance != null) {',
                         'if (reverseWarning) {\n'
                         '        val dynamic = evaluateDynamicGuidance(initializedState, next, '
                         'directedMatch, frame, config)\n'
                         '        if (false /* D-033 reverse E7 result dropped */) {'),
 'reverse-events-suppressed': ('val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, '
                               'frame, config)\n'
                               '        if (dynamic.guidance != null)',
                               'val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, '
                               'frame, config, suppressAnnouncements = true)\n'
                               '        if (dynamic.guidance != null) /* D-033 reverse events suppressed */'),
 'reverse-status-repeat': ('if (!dynamic.state.reverseStatusIssued) {',
                           'if (true /* D-033 reverse status repeats */) {'),
 'elevation-fallback': ('if (!route.elevationUse.used || route.smoothedElevationMeters.isEmpty())',
                        'if (route.smoothedElevationMeters.isEmpty()) /* D-033 E5 fallback ignored */'),
 'priority-old-order': ('const val REMAINING = 500',
                        'const val REMAINING = 300 /* D-033 old priority order */'),
 'elevation-hysteresis-ignore': ('while (elevation >= (band + 1) * config.elevationBoundaryMeters + '
                                 'config.elevationHysteresisMeters)',
                                 'while (elevation >= (band + 1) * config.elevationBoundaryMeters) /* '
                                 'TASK-038 hysteresis ignored */'),
 'elevation-descending-drop': ('while (elevation < band * config.elevationBoundaryMeters - '
                               'config.elevationHysteresisMeters)',
                               'while (false /* TASK-038 descending boundary dropped */)'),
 'elevation-start-boundary': ('state.copy(elevationBand = floor(elevation / '
                              'config.elevationBoundaryMeters).toInt())',
                              'state.copy(elevationBand = floor(elevation / '
                              'config.elevationBoundaryMeters).toInt() + 1) /* TASK-038 start boundary '
                              'off-by-one */'),
 'sunrise-after-start': ('minutes <= 0.0 -> config.sunriseAnnounceMinutes.filter { it !in consumed }.toSet()',
                         'minutes < -1.0 -> config.sunriseAnnounceMinutes.filter { it !in consumed }.toSet() '
                         '/* TASK-038 E8 after-start */'),
 'sunrise-zero-minute': ('minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||',
                         'minutes < -1.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled || /* TASK-038 '
                         'E8 zero-minute */'),
 'sunrise-consumption-refire': ('consumedSunriseThresholds = consumed + newlyCrossed',
                                'consumedSunriseThresholds = consumed /* TASK-038 E8 consumption removed */'),
 'sunrise-day-reset': ('val dayChanged = state.sunriseLocalDay != localDay',
                       'val dayChanged = false /* TASK-038 E8 local-day reset removed */'),
 'sunrise-epoch-day': ('internal fun sunriseEpochSeconds(timestamp: Long, latitude: Double, longitude: '
                       'Double): Double? {\n'
                       '    val localDate = sunriseLocalDate(timestamp, longitude)\n'
                       '    val dayOfYear = localDate.dayOfYear',
                       'internal fun sunriseEpochSeconds(timestamp: Long, latitude: Double, longitude: '
                       'Double): Double? {\n'
                       '    val localDate = sunriseLocalDate(timestamp, longitude)\n'
                       '    val dayOfYear = localDate.toEpochDay().toInt() /* TASK-038 E8 epoch-day drift '
                       '*/'),
 'sunrise-min-interval': ('!config.sunriseEnabled ||\n        !onRoute || !eventIntervalOpen',
                          '!config.sunriseEnabled ||\n'
                          '        !onRoute /* TASK-038 E8 min interval ignored */'),
 'elevation-boundary-config-ignore': ('while (elevation >= (band + 1) * config.elevationBoundaryMeters + '
                                      'config.elevationHysteresisMeters) {',
                                      'while (elevation >= (band + 1) * 100.0 + '
                                      'config.elevationHysteresisMeters) { /* TASK-040 E5 boundary config '
                                      'ignored */'),
 'sunrise-announce-config-ignore': ('        else -> config.sunriseAnnounceMinutes.filter {\n'
                                    '            oldMinutes > it && minutes <= it && it !in consumed\n'
                                    '        }.toSet()',
                                    '        else -> listOf(30, 10).filter {\n'
                                    '            oldMinutes > it && minutes <= it && it !in consumed\n'
                                    '        }.toSet() /* TASK-040 E8 announce config ignored */'),
 'remaining-toggle-ignored': ('if (config.remainingEnabled && onRoute && forward && eventIntervalOpen && '
                              'crossedRemaining.isNotEmpty()) {',
                              'if (onRoute && forward && eventIntervalOpen && crossedRemaining.isNotEmpty()) '
                              '{ /* TASK-040 E3 toggle ignored */'),
 'slope-toggle-ignored': ('if (config.slopeEnabled && onRoute && forward && eventIntervalOpen && index !in '
                          'previous.consumedSlopeIndices) {',
                          'if (onRoute && forward && eventIntervalOpen && index !in '
                          'previous.consumedSlopeIndices) { /* TASK-040 E4 toggle ignored */'),
 'waypoint-toggle-ignored': ('if (config.waypointEnabled && onRoute && forward && eventIntervalOpen && index '
                             '!in previous.consumedWaypointIndices) {',
                             'if (onRoute && forward && eventIntervalOpen && index !in '
                             'previous.consumedWaypointIndices) { /* TASK-040 E6 toggle ignored */'),
 'sunset-toggle-ignored': ('if (!config.sunsetEnabled) return SunsetEvaluation(state, null)',
                           'if (false) return SunsetEvaluation(state, null) /* TASK-040 E7 toggle ignored '
                           '*/'),
 'sunrise-toggle-ignored': ('minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||',
                            'minutes <= 0.0 || newlyCrossed.isEmpty() || false /* TASK-040 E8 toggle ignored '
                            '*/ ||'),
 'sunrise-reverse-suppressed': ('minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||\n'
                                '        !onRoute || !eventIntervalOpen',
                                'minutes <= 0.0 || newlyCrossed.isEmpty() || !config.sunriseEnabled ||\n'
                                '        !onRoute || !eventIntervalOpen || state.direction == '
                                'ProgressDirection.REVERSE /* TASK-040 E8 reverse muted */'),
 'priority-elevation-waypoint-reversed': ('const val ELEVATION = 250\n    const val WAYPOINT = 300',
                                          'const val ELEVATION = 300 /* TASK-040 E5 raised */\n'
                                          '    const val WAYPOINT = 200 /* TASK-040 E6 lowered */'),
 'sunrise-rounding-floor': ('val minutesRemaining = minutes.roundToInt().coerceAtLeast(1)',
                            'val minutesRemaining = minutes.toInt().coerceAtLeast(1) /* TASK-040 E8 rounds '
                            'down */'),
 'sunset-delayed-by-e5-e8': ('val sunsetEvaluation = evaluateSunset(previous, next, frame, config, '
                             'eventIntervalOpen, candidates.isNotEmpty())',
                             'val sunsetEvaluation = evaluateSunset(previous, next, frame, config, '
                             'eventIntervalOpen, candidates.isNotEmpty() || config.elevationEnabled || '
                             'config.sunriseEnabled) /* TASK-040 E5/E8 incorrectly delay E7 */'),
 'sunrise-consumes-rejected-accuracy': ('    if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n'
                                        '        val sunset = evaluateSunsetStandalone(initializedState, '
                                        'frame, config, higherPriority = false)\n'
                                        '        if (sunset.guidance != null) {\n'
                                        '            return GuideResult(sunset.guidance, sunset.state, '
                                        'sunset.reason.copy(rule = "input.accuracy-filter"))\n'
                                        '        }\n'
                                        '        return GuideResult(\n'
                                        '            null,\n'
                                        '            sunset.state,\n'
                                        '            Reason(\n'
                                        '                rule = "input.accuracy-filter",\n'
                                        '                thresholds = mapOf("accuracyRejectMeters" to '
                                        'config.accuracyRejectMeters),\n'
                                        '                details = mapOf("accuracy" to '
                                        'frame.accuracy.toString())\n'
                                        '            )\n'
                                        '        )\n'
                                        '    }',
                                        '    if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {\n'
                                        '        val sunset = evaluateSunsetStandalone(initializedState, '
                                        'frame, config, higherPriority = false)\n'
                                        '        val sunriseState = evaluateSunriseStandalone(sunset.state, '
                                        'frame, config) /* TASK-040 E8 rejected frame consumed */\n'
                                        '        if (sunset.guidance != null) {\n'
                                        '            return GuideResult(sunset.guidance, sunriseState, '
                                        'sunset.reason.copy(rule = "input.accuracy-filter"))\n'
                                        '        }\n'
                                        '        return GuideResult(\n'
                                        '            null,\n'
                                        '            sunriseState,\n'
                                        '            Reason(\n'
                                        '                rule = "input.accuracy-filter",\n'
                                        '                thresholds = mapOf("accuracyRejectMeters" to '
                                        'config.accuracyRejectMeters),\n'
                                        '                details = mapOf("accuracy" to '
                                        'frame.accuracy.toString())\n'
                                        '            )\n'
                                        '        )\n'
                                        '    }'),
 'elevation-reverse-dwell-drop': ('        if (dynamic.guidance != null) {\n'
                                  '            return GuideResult(dynamic.guidance, dynamic.state, '
                                  'dynamic.reason)\n'
                                  '        }\n'
                                  '        if (!dynamic.state.reverseStatusIssued) {',
                                  '        if (dynamic.guidance != null && dynamic.guidance !is '
                                  'Guidance.Elevation) {\n'
                                  '            return GuideResult(dynamic.guidance, dynamic.state, '
                                  'dynamic.reason)\n'
                                  '        }\n'
                                  '        if (!dynamic.state.reverseStatusIssued) {')}

CORE_TEST = frozenset({"core-guide-test"})
OFF_ROUTE_CONSUMERS = frozenset({"core-guide-test", "replay", "phase1-accuracy", "config-sensitivity"})
TURN_CONSUMERS = frozenset({"core-guide-test", "replay", "replay-turn-session"})
TURN_OFF_ROUTE_GATE_CONSUMERS = frozenset({"core-guide-test", "replay"})
TURN_AXIS_CONSUMERS = frozenset({"core-guide-test", "replay-turn-session"})
TURN_OFFSET_CONSUMERS = frozenset({"core-guide-test", "config-sensitivity"})

# Each row says which independent consumer must reject that specific mutation.
# Other consumers still run and their outcomes are logged, but do not decide
# whether the mutation is considered caught.
MUTATION_REQUIRED_CONSUMERS = {
    "dwell-bypass": OFF_ROUTE_CONSUMERS,
    "approach-gate-removed": CORE_TEST,
    "config-constant": OFF_ROUTE_CONSUMERS,
    "unit-heuristic": OFF_ROUTE_CONSUMERS,
    "turn-merge-gap-ignored": CORE_TEST,
    "turn-consumption": frozenset({"core-guide-test", "replay"}),
    "turn-direction-gate": TURN_CONSUMERS,
    # The geometric turn session stays on-route; off-route suppression is
    # covered by the core test and the replay contract probe instead.
    "turn-off-route-gate": TURN_OFF_ROUTE_GATE_CONSUMERS,
    "turn-offset-gate": TURN_OFFSET_CONSUMERS,
    "peak-prominence-ignored": CORE_TEST,
    "elevation-waypoint-mix": CORE_TEST,
    "elevation-always-ok": CORE_TEST,
    "turn-axis-simplified": TURN_AXIS_CONSUMERS,
    "waypoint-near-filter": CORE_TEST,
    "sunset-drop-pending": CORE_TEST,
    "sunset-periodic-gate": CORE_TEST,
    "sunset-no-start": CORE_TEST,
    "sunset-epoch-day": CORE_TEST,
    "sunset-skip-accuracy": CORE_TEST,
    "event-offroute-gate": CORE_TEST,
    "event-reverse-gate": CORE_TEST,
    "event-min-interval": CORE_TEST,
    "event-consumption-queue": CORE_TEST,
    "event-threshold-refire": CORE_TEST,
    "reverse-sunset-drop": CORE_TEST,
    "reverse-events-suppressed": CORE_TEST,
    "reverse-status-repeat": CORE_TEST,
    "elevation-fallback": CORE_TEST,
    "priority-old-order": CORE_TEST,
    "elevation-hysteresis-ignore": CORE_TEST,
    "elevation-descending-drop": CORE_TEST,
    "elevation-start-boundary": CORE_TEST,
    "sunrise-after-start": CORE_TEST,
    "sunrise-zero-minute": CORE_TEST,
    "sunrise-consumption-refire": CORE_TEST,
    "sunrise-day-reset": CORE_TEST,
    "sunrise-epoch-day": CORE_TEST,
    "sunrise-min-interval": CORE_TEST,
    "elevation-boundary-config-ignore": CORE_TEST,
    "sunrise-announce-config-ignore": CORE_TEST,
    "remaining-toggle-ignored": CORE_TEST,
    "slope-toggle-ignored": CORE_TEST,
    "waypoint-toggle-ignored": CORE_TEST,
    "sunset-toggle-ignored": CORE_TEST,
    "sunrise-toggle-ignored": CORE_TEST,
    "sunrise-reverse-suppressed": CORE_TEST,
    "priority-elevation-waypoint-reversed": CORE_TEST,
    "sunrise-rounding-floor": CORE_TEST,
    "sunset-delayed-by-e5-e8": CORE_TEST,
    "sunrise-consumes-rejected-accuracy": CORE_TEST,
    "elevation-reverse-dwell-drop": CORE_TEST,
}

CONSUMER_NAMES = ("replay", "replay-turn-session", "config-sensitivity", "phase1-accuracy")


def unmutated_consumer_failures(results: dict[str, int]) -> list[str]:
    """Fail closed when any downstream consumer rejects the clean engine."""
    failures: list[str] = []
    for name in ("core-guide-test", *CONSUMER_NAMES):
        if name not in results:
            failures.append(f"FAIL: consumer {name} was not run on the unmutated engine")
        elif results[name] != 0:
            failures.append(f"FAIL: consumer {name} fails on the unmutated engine")
    return failures


def mutation_consumer_failures(mutation: str, results: dict[str, int]) -> list[str]:
    """Only consumers named in the mutation table are required to reject it."""
    required = MUTATION_REQUIRED_CONSUMERS.get(mutation)
    if required is None:
        return [f"{mutation}: missing required-consumer table row"]
    failures: list[str] = []
    for name in sorted(required):
        if name not in results:
            failures.append(f"{mutation}: required consumer {name} was not run")
        elif results[name] == 0:
            failures.append(f"{mutation}: required consumer {name} unexpectedly passed")
    return failures


def failed_test_names(workspace: Path) -> list[str]:
    """Read failure/error names from Gradle's XML test results."""
    names: set[str] = set()
    for result_file in (workspace / "core-guide/build/test-results/test").glob("TEST-*.xml"):
        try:
            root = ET.parse(result_file).getroot()
        except ET.ParseError:
            continue
        for case in root.findall(".//testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                names.add(f"{case.get('classname', result_file.stem)}.{case.get('name', 'unknown')}")
    return sorted(names)


def mutation_needle_failures(engine_source: str, route_source: str) -> list[str]:
    """Require every mutation needle to identify exactly one source site."""
    failures: list[str] = []
    route_mutations = {"turn-axis-simplified", "elevation-waypoint-mix", "elevation-always-ok", "waypoint-near-filter", "peak-prominence-ignored"}
    for name, (needle, _) in VARIANTS.items():
        source = route_source if name in route_mutations else engine_source
        count = source.count(needle)
        if count != 1:
            failures.append(f"{name}: mutation needle occurrence count={count}, expected=1")
    return failures


def consumer_commands(cli: Path) -> dict[str, list[str]]:
    return {
        "replay": ["python", "tools/replay.py", "--cli", str(cli), "--contract"],
        "replay-turn-session": [
            "python", "tools/replay.py", "testdata/sessions/golden/turn_contract",
            "--cli", str(cli), "--strict",
        ],
        "config-sensitivity": ["python", "tools/config_sensitivity.py", "--cli", str(cli)],
        "phase1-accuracy": [
            "python", "tools/phase1_accuracy.py", "--cli", str(cli), "--contract",
            "--run-id", "d033", "--commit-sha", "fixture",
        ],
    }


def installed_cli(workspace: Path, *, windows: bool | None = None) -> Path:
    """Select the launcher executable for the host running the consumer."""
    if windows is None:
        windows = os.name == "nt"
    windows_cli = workspace / "replay/build/install/replay/bin/replay.bat"
    unix_cli = workspace / "replay/build/install/replay/bin/replay"
    candidates = (windows_cli, unix_cli) if windows else (unix_cli, windows_cli)
    return next((candidate for candidate in candidates if candidate.is_file()), candidates[0])


def run(command: list[str], cwd: Path) -> tuple[int, str]:
    if command and Path(command[0]).suffix.lower() in {".bat", ".cmd"}:
        command = ["cmd", "/c", *command]
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
    summary_rows: list[tuple[str, str, str, str]] = []
    if set(MUTATION_REQUIRED_CONSUMERS) != set(VARIANTS):
        missing = sorted(set(VARIANTS) - set(MUTATION_REQUIRED_CONSUMERS))
        extra = sorted(set(MUTATION_REQUIRED_CONSUMERS) - set(VARIANTS))
        failures.append(f"mutation consumer table mismatch missing={missing} extra={extra}")

    def save_evidence() -> None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        table = [
            "D-033 mutation rejection summary",
            "mutation | required consumers | rejected by | failed test/contract",
            "--- | --- | --- | ---",
        ]
        table.extend(" | ".join(row) for row in summary_rows)
        args.out.write_text("\n\n".join(evidence + ["\n".join(table)]) + "\n", encoding="utf-8")

    with tempfile.TemporaryDirectory(prefix="tnapp-d033-") as temporary:
        workspace = Path(temporary) / "repo"
        copy_repo(source, workspace)
        engine = workspace / ENGINE
        original = engine.read_text(encoding="utf-8")
        route = workspace / "core-guide/src/main/kotlin/com/trailnav/core/Route.kt"
        route_original = route.read_text(encoding="utf-8")
        needle_errors = mutation_needle_failures(original, route_original)
        if needle_errors:
            failures.extend(needle_errors)
            evidence.append("FAIL: mutation needles must be unique\n" + "\n".join(needle_errors))
            save_evidence()
            print("FAIL: D-033 mutation needle uniqueness")
            print("\n".join(needle_errors))
            return 1

        # Positive control runs first: a consumer that already fails against
        # the clean engine cannot count as evidence that it rejected a mutant.
        baseline_core_code, baseline_core_output = run([args.gradle, ":core-guide:test", "--no-daemon"], workspace)
        evidence.append(f"UNMUTATED consumer=core-guide-test exit={baseline_core_code}\n{baseline_core_output}")
        baseline_build_code, baseline_build_output = run([args.gradle, ":replay:installDist", "--no-daemon"], workspace)
        evidence.append(f"UNMUTATED consumer=replay-build exit={baseline_build_code}\n{baseline_build_output}")
        baseline_cli = installed_cli(workspace)
        baseline_results = {"core-guide-test": baseline_core_code}
        if baseline_build_code != 0 or not baseline_cli.is_file():
            failures.append("FAIL: consumer replay CLI build fails on the unmutated engine")
            for name in CONSUMER_NAMES:
                baseline_results[name] = -1
        else:
            for consumer, command in consumer_commands(baseline_cli).items():
                code, output = run(command, workspace)
                baseline_results[consumer] = code
                evidence.append(f"UNMUTATED consumer={consumer} exit={code}\n{output}")
        failures.extend(unmutated_consumer_failures(baseline_results))
        if failures:
            save_evidence()
            print("FAIL: D-033 unmutated-engine positive control")
            print("\n".join(failures))
            return 1

        for name, (needle, replacement) in VARIANTS.items():
            target = route if name in {
                "turn-axis-simplified", "elevation-waypoint-mix", "elevation-always-ok", "waypoint-near-filter",
                "peak-prominence-ignored",
            } else engine
            target_original = route_original if target == route else original
            variant_engine = target_original.replace(needle, replacement, 1)
            assert variant_engine != target_original
            target.write_text(variant_engine, encoding="utf-8")
            shutil.rmtree(workspace / "core-guide/build/test-results/test", ignore_errors=True)
            test_code, test_output = run([args.gradle, ":core-guide:test", "--no-daemon"], workspace)
            test_names = failed_test_names(workspace)
            evidence.append(
                f"VARIANT {name} consumer=core-guide-test exit={test_code} "
                f"failed_tests={','.join(test_names) if test_names else 'none'}\n{test_output}"
            )
            consumer_results = {"core-guide-test": test_code}

            # A failing test task prevents Gradle from reaching installDist in
            # the same invocation. Build the real CLI separately so each
            # downstream consumer is exercised against the mutated engine.
            cli_code, cli_output = run([args.gradle, ":replay:installDist", "--no-daemon"], workspace)
            evidence.append(f"VARIANT {name} consumer=replay-build exit={cli_code}\n{cli_output}")
            if cli_code != 0:
                failures.append(f"{name}: replay CLI build failed")
            cli = installed_cli(workspace)
            if cli_code != 0 or not cli.is_file():
                for consumer in CONSUMER_NAMES:
                    consumer_results[consumer] = -1
                    evidence.append(f"VARIANT {name} consumer={consumer} exit=not-built")
            else:
                for consumer, command in consumer_commands(cli).items():
                    code, output = run(command, workspace)
                    consumer_results[consumer] = code
                    evidence.append(f"VARIANT {name} consumer={consumer} exit={code}\n{output}")

            required = MUTATION_REQUIRED_CONSUMERS[name]
            variant_failures = mutation_consumer_failures(name, consumer_results)
            failures.extend(variant_failures)
            if test_code != 0 and not test_names:
                failures.append(f"{name}: core-guide-test failed without a named failing test")
            rejected = sorted(consumer for consumer in required if consumer_results.get(consumer, 0) != 0)
            failed_tests = [
                f"{consumer}: {', '.join(test_names)}"
                if consumer == "core-guide-test" and test_names
                else f"{consumer}: contract/replay check"
                for consumer in rejected
            ]
            summary_rows.append((name, ", ".join(sorted(required)), ", ".join(rejected), "; ".join(failed_tests)))
            target.write_text(target_original, encoding="utf-8")

        fixture = workspace / "testdata/sessions/golden/turn_contract"
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
    save_evidence()
    if failures:
        print("FAIL: D-033 negative control")
        print("\n".join(failures))
        return 1
    print(f"PASS: D-033 consumers rejected {len(VARIANTS)} engine mutations")
    print(f"evidence={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
