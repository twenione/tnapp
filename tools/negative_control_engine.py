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
VARIANTS = {
 'eta-no-grade': ('exp(-3.5 * abs(slope + 0.05))',
                  'exp(-3.5 * abs(0.0 + 0.05))'),
 'eta-target-ignores-switch': ('progressMeters >= switchMeters',
                  'false /* D-033 summit switch ignored */'),
 'eta-correction-includes-stops': ('distanceMeters > 0.0 && distanceMeters / elapsedSeconds >= config.etaMinMovingSpeedMps',
                  'true /* D-033 stopped interval admitted */'),
 'eta-window-ignored': ('return (endElevation - startElevation) / windowLengthMeters',
                  'return (route.smoothedElevationMeters[segmentIndex + 1] - route.smoothedElevationMeters[segmentIndex]) / (route.cumulativeMeters[segmentIndex + 1] - route.cumulativeMeters[segmentIndex])'),
 'eta-floor-removed': ('val speedMetersPerSecond = max(rawSpeedMetersPerSecond, config.etaMinSegmentSpeedMps)',
                  'val speedMetersPerSecond = rawSpeedMetersPerSecond /* D-033 segment speed floor removed */'),
 'eta-cap-removed': ('rawSeconds > config.etaMaxTotalSeconds -> "total-exceeds-max"',
                  'false -> "total-exceeds-max" /* D-033 total ETA cap removed */'),
 'eta-clamped-observation-included': ('if (!prediction.clampedAnySegment) {',
                  'if (true /* D-033 clamped observation included */) {'),
 'arrival-advance-removed': ('val arrivalHasAdvance = next.arrivalBaselineRemainingMeters != null &&\n'
                             '        next.advanceCreditedMeters >= requiredArrivalAdvance',
                             'val arrivalHasAdvance = true /* TASK-079 advance condition removed */'),
 'arrival-jump-always-credited': ('if (jump <= allowed) {',
                                  'if (true) { /* TASK-079 jump limit removed */'),
 'arrival-floor-removed': ('val absoluteFloor = min(config.arrivalMinAdvanceMeters, config.arrivalAdvanceFraction * route.totalLengthMeters)',
                           'val absoluteFloor = 0.0 /* TASK-079 absolute floor removed */'),
 'arrival-positive-deltas-summed': ('if (projected > highWater) {\n'
                                    '        val jump = projected - previousProjected\n'
                                    '        val credited = if (jump <= allowed) {\n'
                                    '            state.advanceCreditedMeters + projected - highWater\n'
                                    '        } else {\n'
                                    '            state.advanceCreditedMeters\n'
                                    '        }\n'
                                    '        return state.copy(advanceHighWaterMeters = projected, advanceCreditedMeters = credited)\n'
                                    '    }',
                                    'if (projected > previousProjected) {\n'
                                    '        val jump = projected - previousProjected\n'
                                    '        val credited = if (jump <= allowed) {\n'
                                    '            state.advanceCreditedMeters + projected - previousProjected /* TASK-079 jitter summed */\n'
                                    '        } else {\n'
                                    '            state.advanceCreditedMeters\n'
                                    '        }\n'
                                    '        return state.copy(advanceHighWaterMeters = projected, advanceCreditedMeters = credited)\n'
                                    '    }'),
 'arrival-backward-highwater-retained': ('return state.copy(advanceHighWaterMeters = projected)',
                                         'return state /* TASK-079 backward high-water retained */'),
 'arrival-record-every-frame': ('if (arrivalCandidate && !arrivalHasAdvance && next.arrivalBlockedRecords.isEmpty()) {',
                                'if (arrivalCandidate && !arrivalHasAdvance) { /* TASK-079 record every frame */'),
 'arrival-fraction-hardcoded': ('val proportional = baselineRemainingMeters?.let { config.arrivalAdvanceFraction * it } ?: 0.0',
                                'val proportional = baselineRemainingMeters?.let { 0.8 * it } ?: 0.0 /* TASK-079 fraction hardcoded */'),
 'arrival-baseline-approach-frame': ('if (state.arrivalBaselineRemainingMeters == null && match.distanceMeters < config.offRouteExitDistMeters) {',
                                     'if (state.arrivalBaselineRemainingMeters == null) { /* TASK-079 approach frame used */'),
 'arrival-jump-tolerance-default-20': ('val arrivalJumpToleranceMeters: Double = 40.0,',
                                       'val arrivalJumpToleranceMeters: Double = 20.0, /* D-175 tolerance default regressed */'),
 'dwell-bypass': ('elapsedSeconds(timestamp, since) >= config.offRouteEnterDwellSeconds',
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
 'turn-pass-always-false': ('return match.projectedMeters >= turn.s',
                            'return false /* D-033 turn pass always false */'),
 'turn-identity-zero': ('Guidance.TurnNow(turn.side, index)',
                        'Guidance.TurnNow(turn.side, 0) /* D-033 turn identity zero */'),
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
 'elevation-index-window': ('return distance <= halfWindowMeters + roundingTolerance',
                            'return abs(sampleIndex - centerIndex) <= 2 /* D-033 elevation index window */'),
 'elevation-grid-skipped': ('val gridDistances = elevationGridDistances(totalLengthMeters, config.elevationGridMeters)',
                            'val gridDistances = cumulative /* D-033 elevation grid skipped */'),
 'elevation-endpoints-smoothed': ('if (index == 0 || index == profile.lastIndex) profile[index]',
                                  'if (false) profile[index] /* D-033 endpoints smoothed */'),
 'elevation-peaks-on-points': ('extractPeaks(elevation.gridDistances, elevation.gridSmoothed, config)',
                               'extractPeaks(cumulative, elevation.smoothed, config) /* D-033 peaks on points */'),
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
 'event-reverse-gate': ('if (!config.slopeEnabled || !forward) {',
                        'if (!config.slopeEnabled) { /* D-033 reverse E4 gate removed */'),
 'event-min-interval': ('        } else if (!eventIntervalOpen) {\n'
                        '            freshCrossed.forEach { record("E1", it.toString(), "interval") }\n'
                        '        } else if (config.milestoneEnabled && onRoute && forward && eventIntervalOpen && '
                        'freshCrossed.isNotEmpty()) {',
                        '        } else if (config.milestoneEnabled && onRoute && forward && '
                        'freshCrossed.isNotEmpty()) { /* D-033 event interval removed */'),
 'event-consumption-queue': ('next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices + '
                             'crossed)',
                             'next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices) /* '
                             'D-033 E1 consumption removed */'),
 'event-threshold-refire': ('if (index in consumedSlopeIndices) return@forEachIndexed',
                            'if (false /* D-033 consumed E4 threshold refires */) return@forEachIndexed'),
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
 'remaining-drop-pending': ('val pendingRemaining = if (retainCrossedRemaining) {\n'
                            '        pendingRemainingBefore + crossedRemaining\n'
                            '    } else {\n'
                            '        pendingRemainingBefore\n'
                            '    }',
                            'val pendingRemaining = emptySet<Double>() /* D-071 E3 pending dropped */'),
 'remaining-pending-interval-ignored': ('config.remainingEnabled && onRoute && forward && eventIntervalOpen &&\n'
                                        '        next.pendingRemainingThresholds.isNotEmpty()',
                                        'config.remainingEnabled && onRoute && forward &&\n'
                                        '        next.pendingRemainingThresholds.isNotEmpty() /* D-071 min interval ignored */'),
 'remaining-pending-reverse-spoken': ('config.remainingEnabled && onRoute && forward && eventIntervalOpen &&\n'
                                      '        next.pendingRemainingThresholds.isNotEmpty()',
                                      'config.remainingEnabled && onRoute && eventIntervalOpen &&\n'
                                      '        next.pendingRemainingThresholds.isNotEmpty() /* D-071 reverse pending spoken */'),
 'remaining-delayed-threshold-text': ('Guidance.Remaining(threshold, remaining)',
                                      'Guidance.Remaining(threshold, threshold) /* D-071 delayed threshold text */'),
 'remaining-pending-not-cleared': ('val remainingPendingAfterSelection = next.pendingRemainingThresholds - selected.remainingThresholds\n'
                                   '    next = next.copy(\n'
                                   '        pendingSunsetThresholds = pending,\n'
                                   '        pendingSunsetDelayReason = if (selected.sunsetThresholds.isNotEmpty()) null else next.pendingSunsetDelayReason,\n'
                                   '        pendingRemainingThresholds = remainingPendingAfterSelection,',
                                   'val remainingPendingAfterSelection = next.pendingRemainingThresholds - selected.remainingThresholds\n'
                                   '    next = next.copy(\n'
                                   '        pendingSunsetThresholds = pending,\n'
                                   '        pendingSunsetDelayReason = if (selected.sunsetThresholds.isNotEmpty()) null else next.pendingSunsetDelayReason,\n'
                                   '        pendingRemainingThresholds = next.pendingRemainingThresholds /* D-071 pending not cleared */,'),
 'remaining-reverse-pending': ('val retainCrossedRemaining = config.remainingEnabled && forward && crossedRemaining.isNotEmpty()',
                               'val retainCrossedRemaining = config.remainingEnabled && crossedRemaining.isNotEmpty() /* D-071 reverse retained */'),
 'remaining-toggle-ignored': ('val retainCrossedRemaining = config.remainingEnabled && forward && crossedRemaining.isNotEmpty()',
                              'val retainCrossedRemaining = forward && crossedRemaining.isNotEmpty() /* TASK-040 E3 toggle ignored */'),
 'slope-toggle-ignored': ('if (!config.slopeEnabled || !forward) {',
                          'if (!forward) { /* TASK-040 E4 toggle ignored */'),
 'slope-defer-ignored': ('val pendingSlopePeaks = next.pendingSlopePeaks.toMutableMap()\n'
                         '    val consumedSlopeIndices = next.consumedSlopeIndices.toMutableSet()',
                         'val consumedSlopeIndices = next.consumedSlopeIndices.toMutableSet().apply {\n'
                         '        addAll(next.pendingSlopePeaks.keys)\n'
                         '    }\n'
                         '    val pendingSlopePeaks = mutableMapOf<Int, PendingSlopePeak>() /* D-033 E4 pending consumed */'),
 'slope-discard-ignored': ('val pending = pendingSlopePeaks.remove(index)',
                           'val pending = pendingSlopePeaks.remove(index)\n'
                           '            if (pending != null) {\n'
                           '                val details = linkedMapOf(\n'
                           '                    "peakIndex" to index.toString(),\n'
                           '                    "peakS" to pending.peakS.toString(),\n'
                           '                    "delayed" to "true",\n'
                           '                    "delayReason" to pending.delayReason,\n'
                           '                )\n'
                           '                candidates += DynamicCandidate(\n'
                           '                    EventPriority.SLOPE, "event.slope",\n'
                           '                    Guidance.Slope(SlopeKind.ASCENT, peak.climbMeters),\n'
                           '                    reason("event.slope", "E4", details),\n'
                           '                    slopeIndices = setOf(index),\n'
                           '                ) /* D-033 E4 speaks after passing peak */\n'
                           '            }'),
 'slope-discard-unrecorded': ('                discardedSlopePeaks += DiscardedSlopePeak(\n'
                               '                    index = index,\n'
                               '                    peakS = pending.peakS,\n'
                               '                    passedProjectedMeters = match.projectedMeters,\n'
                               '                    delayReason = pending.delayReason,\n'
                               '                )',
                               '                // D-033 E4 discard record omitted'),
 'task078-e4-disabled-record-omitted': ('                if ((distance >= 0.0 && distance <= config.peakAnnounceLeadMeters) || pending != null) {',
                                        '                if (((distance >= 0.0 && distance <= config.peakAnnounceLeadMeters) || pending != null) && config.slopeEnabled) { /* TASK-078 disabled record omitted */'),
 'task078-e4-reverse-record-omitted': ('                if ((distance >= 0.0 && distance <= config.peakAnnounceLeadMeters) || pending != null) {',
                                       '                if (((distance >= 0.0 && distance <= config.peakAnnounceLeadMeters) || pending != null) && state.direction != ProgressDirection.REVERSE) { /* TASK-078 reverse record omitted */'),
 'task078-e6-disabled-record-omitted': ('                if (discardReason != null) {',
                                        '                if (discardReason != null && discardReason != "disabled") { /* TASK-078 disabled record omitted */'),
 'task078-e6-offroute-record-omitted': ('                if (discardReason != null) {',
                                       '                if (discardReason != null && discardReason != "offroute") { /* TASK-078 offroute record omitted */'),
 'task078-e6-reverse-record-omitted': ('                if (discardReason != null) {',
                                      '                if (discardReason != null && discardReason != "reverse") { /* TASK-078 reverse record omitted */'),
 'task078-e6-interval-record-omitted': ('                if (discardReason != null) {',
                                       '                if (discardReason != null && discardReason != "interval") { /* TASK-078 interval record omitted */'),
 'task078-e6-priority-record-omitted': ('    (waypointCandidateIndices - selectedWaypointIndices).forEach { index ->',
                                       '    (waypointCandidateIndices - selectedWaypointIndices).filter { false }.forEach { index -> /* TASK-078 priority record omitted */'),
  'task080-e1-disabled-record-omitted': ('!config.milestoneEnabled -> "disabled"',
                                         'false -> "disabled" /* TASK-080 E1 disabled record omitted */'),
  'task080-e1-offroute-record-omitted': ('!config.milestoneEnabled -> "disabled"\n            !onRoute -> "offroute"',
                                         '!config.milestoneEnabled -> "disabled"\n            false -> "offroute" /* TASK-080 E1 offroute record omitted */'),
  'task080-e1-reverse-record-omitted': ('!onRoute -> "offroute"\n            state.direction == ProgressDirection.REVERSE -> "reverse"',
                                        '!onRoute -> "offroute"\n            false -> "reverse" /* TASK-080 E1 reverse record omitted */'),
  'task080-e1-direction-unknown-record-omitted': ('state.direction == ProgressDirection.REVERSE -> "reverse"\n            state.direction == ProgressDirection.UNKNOWN -> "direction-unknown"',
                                                   'state.direction == ProgressDirection.REVERSE -> "reverse"\n            false -> "direction-unknown" /* TASK-080 E1 direction-unknown record omitted */'),
  'task080-e1-interval-record-omitted': ('freshCrossed.forEach { record("E1", it.toString(), "interval") }',
                                         '/* TASK-080 E1 interval record omitted */'),
  'task080-e1-priority-record-omitted': ('recordOnPriority = setOf("E1" to index.toString()),',
                                         'recordOnPriority = emptySet(), /* TASK-080 E1 priority record omitted */'),
  'task080-e1-jump-record-omitted': ('freshCrossed.filterNot { it == index }.forEach { record("E1", it.toString(), "jump") }',
                                    '/* TASK-080 E1 jump record omitted */'),
  'task080-e2-disabled-record-omitted': ('!config.elapsedEnabled -> "disabled"',
                                         'false -> "disabled" /* TASK-080 E2 disabled record omitted */'),
  'task080-e2-offroute-record-omitted': ('!config.elapsedEnabled -> "disabled"\n            !onRoute -> "offroute"',
                                         '!config.elapsedEnabled -> "disabled"\n            false -> "offroute" /* TASK-080 E2 offroute record omitted */'),
  'task080-e2-interval-record-omitted': ('!config.elapsedEnabled -> "disabled"\n            !onRoute -> "offroute"\n            !eventIntervalOpen -> "interval"',
                                         '!config.elapsedEnabled -> "disabled"\n            !onRoute -> "offroute"\n            false -> "interval" /* TASK-080 E2 interval record omitted */'),
  'task080-e2-priority-record-omitted': ('recordOnPriority = setOf("E2" to index.toString()),',
                                         'recordOnPriority = emptySet(), /* TASK-080 E2 priority record omitted */'),
  'task080-e2-jump-record-omitted': ('freshCrossed.filterNot { it == index }.forEach { record("E2", it.toString(), "jump") }',
                                    '/* TASK-080 E2 jump record omitted */'),
  'task080-e3-disabled-record-omitted': ('!config.remainingEnabled -> "disabled"',
                                         'false -> "disabled" /* TASK-080 E3 disabled record omitted */'),
  'task080-e3-reverse-record-omitted': ('!config.remainingEnabled -> "disabled"\n        state.direction == ProgressDirection.REVERSE -> "reverse"',
                                        '!config.remainingEnabled -> "disabled"\n        false -> "reverse" /* TASK-080 E3 reverse record omitted */'),
  'task080-e3-direction-unknown-record-omitted': ('state.direction == ProgressDirection.REVERSE -> "reverse"\n        state.direction == ProgressDirection.UNKNOWN -> "direction-unknown"',
                                                   'state.direction == ProgressDirection.REVERSE -> "reverse"\n        false -> "direction-unknown" /* TASK-080 E3 direction-unknown record omitted */'),
  'task080-e8-disabled-record-omitted': ('!config.sunriseEnabled -> "disabled"',
                                         'false -> "disabled" /* TASK-080 E8 disabled record omitted */'),
  'task080-e8-offroute-record-omitted': ('!config.sunriseEnabled -> "disabled"\n        !onRoute -> "offroute"',
                                         '!config.sunriseEnabled -> "disabled"\n        false -> "offroute" /* TASK-080 E8 offroute record omitted */'),
  'task080-e8-interval-record-omitted': ('!config.sunriseEnabled -> "disabled"\n        !onRoute -> "offroute"\n        !eventIntervalOpen -> "interval"',
                                         '!config.sunriseEnabled -> "disabled"\n        !onRoute -> "offroute"\n        false -> "interval" /* TASK-080 E8 interval record omitted */'),
  'task080-e8-priority-record-omitted': ('recordOnPriority = newlyCrossed.map { "E8" to it.toString() }.toSet(),',
                                         'recordOnPriority = emptySet(), /* TASK-080 E8 priority record omitted */'),
  'task080-e4-jump-record-omitted': ('                discardedSlopePeaks += DiscardedSlopePeak(\n                    index = index,\n                    peakS = peak.s,\n                    passedProjectedMeters = match.projectedMeters,\n                    delayReason = "none",\n                    reason = "jump",\n                )',
                                      '                // TASK-080 E4 jump record omitted'),
  'task080-e6-jump-record-omitted': ('                discardedWaypoints += DiscardedWaypoint(index, waypoint.s, distance, "jump")',
                                      '                // TASK-080 E6 jump record omitted'),
  'task080-first-match-e4-exclusion-removed': ('if (next.firstMatchProgressMeters?.let { peak.s < it } == true) {',
                                              'if (false) { /* TASK-080 E4 first-match exclusion removed */'),
  'task080-first-match-e6-exclusion-removed': ('if (next.firstMatchProgressMeters?.let { waypoint.s < it } == true) {',
                                              'if (false) { /* TASK-080 E6 first-match exclusion removed */'),
  'task080-duplicate-discard-record': ('                progressMeters = match.projectedMeters,\n            ),\n        )\n    }\n\n    // E1: distance milestones are based on projected route distance.',
                                      '                progressMeters = match.projectedMeters,\n            ),\n        )\n        if (next.discardedEvents.isNotEmpty()) {\n            next = next.copy(discardedEvents = next.discardedEvents + next.discardedEvents.last())\n        }\n    }\n\n    // E1: distance milestones are based on projected route distance. /* TASK-080 duplicate record */'),
  'task080-invalid-e3-record-reason': ('record("E3", threshold.toBigDecimal().stripTrailingZeros().toPlainString(), discardReason)',
                                       'record("E3", threshold.toBigDecimal().stripTrailingZeros().toPlainString(), "interval") /* TASK-080 invalid reason */'),
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

ROUTE_SOURCE = Path("core-guide/src/main/kotlin/com/trailnav/core/Route.kt")
ETA_SOURCE = Path("core-guide/src/main/kotlin/com/trailnav/core/RouteEta.kt")
TURN_PASS_SOURCE = Path("core-guide/src/main/kotlin/com/trailnav/core/TurnPass.kt")
MODEL_SOURCE = Path("core-guide/src/main/kotlin/com/trailnav/core/Model.kt")
VARIANT_SOURCE_PATHS = {
    "turn-pass-always-false": TURN_PASS_SOURCE,
    "turn-axis-simplified": ROUTE_SOURCE,
    "elevation-waypoint-mix": ROUTE_SOURCE,
    "elevation-index-window": ROUTE_SOURCE,
    "elevation-grid-skipped": ROUTE_SOURCE,
    "elevation-endpoints-smoothed": ROUTE_SOURCE,
    "elevation-peaks-on-points": ROUTE_SOURCE,
    "elevation-always-ok": ROUTE_SOURCE,
    "waypoint-near-filter": ROUTE_SOURCE,
    "peak-prominence-ignored": ROUTE_SOURCE,
    "eta-no-grade": ETA_SOURCE,
    "eta-target-ignores-switch": ETA_SOURCE,
    "eta-correction-includes-stops": ETA_SOURCE,
    "eta-window-ignored": ETA_SOURCE,
    "eta-floor-removed": ETA_SOURCE,
    "eta-cap-removed": ETA_SOURCE,
    "eta-clamped-observation-included": ETA_SOURCE,
    "arrival-jump-tolerance-default-20": MODEL_SOURCE,
}
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
    "turn-pass-always-false": CORE_TEST,
    "turn-identity-zero": CORE_TEST,
    "turn-consumption": frozenset({"core-guide-test", "replay"}),
    "turn-direction-gate": TURN_CONSUMERS,
    # The geometric turn session stays on-route; off-route suppression is
    # covered by the core test and the replay contract probe instead.
    "turn-off-route-gate": TURN_OFF_ROUTE_GATE_CONSUMERS,
    "turn-offset-gate": TURN_OFFSET_CONSUMERS,
    "peak-prominence-ignored": CORE_TEST,
    "elevation-waypoint-mix": CORE_TEST,
    "elevation-index-window": CORE_TEST,
    "elevation-grid-skipped": CORE_TEST,
    "elevation-endpoints-smoothed": CORE_TEST,
    "elevation-peaks-on-points": CORE_TEST,
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
    "remaining-drop-pending": CORE_TEST,
    "remaining-pending-interval-ignored": CORE_TEST,
    "remaining-pending-reverse-spoken": CORE_TEST,
    "remaining-delayed-threshold-text": CORE_TEST,
    "remaining-pending-not-cleared": CORE_TEST,
    "remaining-reverse-pending": CORE_TEST,
    "remaining-toggle-ignored": CORE_TEST,
    "slope-toggle-ignored": CORE_TEST,
    "slope-defer-ignored": CORE_TEST,
    "slope-discard-ignored": CORE_TEST,
    "slope-discard-unrecorded": CORE_TEST,
    "task078-e4-disabled-record-omitted": CORE_TEST,
    "task078-e4-reverse-record-omitted": CORE_TEST,
    "task078-e6-disabled-record-omitted": CORE_TEST,
    "task078-e6-offroute-record-omitted": CORE_TEST,
    "task078-e6-reverse-record-omitted": CORE_TEST,
    "task078-e6-interval-record-omitted": CORE_TEST,
    "task078-e6-priority-record-omitted": CORE_TEST,
    "waypoint-toggle-ignored": CORE_TEST,
    "sunset-toggle-ignored": CORE_TEST,
    "sunrise-toggle-ignored": CORE_TEST,
    "sunrise-reverse-suppressed": CORE_TEST,
    "priority-elevation-waypoint-reversed": CORE_TEST,
    "sunrise-rounding-floor": CORE_TEST,
    "sunset-delayed-by-e5-e8": CORE_TEST,
    "sunrise-consumes-rejected-accuracy": CORE_TEST,
    "elevation-reverse-dwell-drop": CORE_TEST,
    "eta-no-grade": CORE_TEST,
    "eta-target-ignores-switch": CORE_TEST,
    "eta-correction-includes-stops": CORE_TEST,
    "eta-window-ignored": CORE_TEST,
    "eta-floor-removed": CORE_TEST,
    "eta-cap-removed": CORE_TEST,
    "eta-clamped-observation-included": CORE_TEST,
    "arrival-advance-removed": CORE_TEST,
    "arrival-jump-always-credited": CORE_TEST,
    "arrival-floor-removed": CORE_TEST,
    "arrival-positive-deltas-summed": CORE_TEST,
    "arrival-backward-highwater-retained": CORE_TEST,
    "arrival-record-every-frame": CORE_TEST,
    "arrival-fraction-hardcoded": CORE_TEST,
    "arrival-baseline-approach-frame": CORE_TEST,
    "arrival-jump-tolerance-default-20": CORE_TEST,
    "task080-e1-disabled-record-omitted": CORE_TEST,
    "task080-e1-offroute-record-omitted": CORE_TEST,
    "task080-e1-reverse-record-omitted": CORE_TEST,
    "task080-e1-direction-unknown-record-omitted": CORE_TEST,
    "task080-e1-interval-record-omitted": CORE_TEST,
    "task080-e1-priority-record-omitted": CORE_TEST,
    "task080-e1-jump-record-omitted": CORE_TEST,
    "task080-e2-disabled-record-omitted": CORE_TEST,
    "task080-e2-offroute-record-omitted": CORE_TEST,
    "task080-e2-interval-record-omitted": CORE_TEST,
    "task080-e2-priority-record-omitted": CORE_TEST,
    "task080-e2-jump-record-omitted": CORE_TEST,
    "task080-e3-disabled-record-omitted": CORE_TEST,
    "task080-e3-reverse-record-omitted": CORE_TEST,
    "task080-e3-direction-unknown-record-omitted": CORE_TEST,
    "task080-e8-disabled-record-omitted": CORE_TEST,
    "task080-e8-offroute-record-omitted": CORE_TEST,
    "task080-e8-interval-record-omitted": CORE_TEST,
    "task080-e8-priority-record-omitted": CORE_TEST,
    "task080-e4-jump-record-omitted": CORE_TEST,
    "task080-e6-jump-record-omitted": CORE_TEST,
    "task080-first-match-e4-exclusion-removed": CORE_TEST,
    "task080-first-match-e6-exclusion-removed": CORE_TEST,
    "task080-duplicate-discard-record": CORE_TEST,
    "task080-invalid-e3-record-reason": CORE_TEST,
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


def load_structurally_unreachable_mutations(repo_root: Path, variant_names: set[str]) -> dict[str, tuple[str, str]]:
    """Resolve D-179 exemptions from the shared data file, never a local list."""
    data_path = repo_root / "tools" / "known_exceptions.json"
    try:
        data = json.loads(data_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read structurally_unreachable data ({type(exc).__name__})") from exc
    if not isinstance(data, dict) or not isinstance(data.get("structurally_unreachable"), list):
        raise ValueError("known_exceptions.json must contain a structurally_unreachable list")

    result: dict[str, tuple[str, str]] = {}
    seen: set[tuple[str, str]] = set()
    for index, entry in enumerate(data["structurally_unreachable"]):
        if not isinstance(entry, dict):
            raise ValueError(f"structurally_unreachable[{index}] must be an object")
        event, reason = entry.get("event"), entry.get("reason")
        if not isinstance(event, str) or not event or not isinstance(reason, str) or not reason:
            raise ValueError(f"structurally_unreachable[{index}] must contain event and reason")
        pair = (event, reason)
        if pair in seen:
            raise ValueError(f"duplicate structurally_unreachable pair {event}/{reason}")
        seen.add(pair)
        mutation = f"task080-{event.lower()}-{reason}-record-omitted"
        if mutation not in variant_names:
            raise ValueError(f"no D-033 mutation corresponds to structurally_unreachable pair {event}/{reason}")
        result[mutation] = pair
    return result


def mutation_outcome(
    mutation: str,
    results: dict[str, int],
    structurally_unreachable_mutations: dict[str, tuple[str, str]],
) -> tuple[str, list[str]]:
    """Return DETECTED, NOT-DETECTABLE, or SURVIVED with strict D-179 handling."""
    required = MUTATION_REQUIRED_CONSUMERS.get(mutation)
    if required is None:
        return "INVALID", [f"{mutation}: missing required-consumer table row"]
    if mutation in structurally_unreachable_mutations:
        missing = sorted(name for name in required if name not in results)
        if missing:
            return "INVALID", [f"{mutation}: required consumer {name} was not run" for name in missing]
        detected = sorted(name for name in required if results[name] != 0)
        if detected:
            message = (
                f"{mutation}: 면제된 변이가 검출됨 — 도달 가능해졌으므로 목록에서 빼고 "
                f"필수로 되돌릴 것 (D-179); consumers={detected}"
            )
            return "DETECTED (unexpected)", [message]
        return "NOT-DETECTABLE (D-179)", []

    failures = mutation_consumer_failures(mutation, results)
    missing = [name for name in sorted(required) if name not in results]
    if missing:
        return "INVALID", failures
    if any(results[name] == 0 for name in required):
        return "SURVIVED", failures
    return "DETECTED", failures


def mutation_is_survivor(status: str) -> bool:
    return status == "SURVIVED"


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


def mutation_needle_failures(sources: dict[Path, str]) -> list[str]:
    """Require each mutation needle to identify one site in its target source."""
    failures: list[str] = []
    for name, (needle, _) in VARIANTS.items():
        target = VARIANT_SOURCE_PATHS.get(name, ENGINE)
        count = sources[target].count(needle)
        if count != 1:
            failures.append(f"{name}: mutation needle occurrence count={count}, expected=1 in {target}")
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
    ignored = shutil.ignore_patterns(".git", ".gradle", ".gradle-home", "build", "*.jar", "*.log", ".task005-evidence")
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
            "mutation | required consumers | status | failed test/contract",
            "--- | --- | --- | ---",
        ]
        table.extend(" | ".join(row) for row in summary_rows)
        args.out.write_text("\n\n".join(evidence + ["\n".join(table)]) + "\n", encoding="utf-8")

    with tempfile.TemporaryDirectory(prefix="tnapp-d033-") as temporary:
        workspace = Path(temporary) / "repo"
        copy_repo(source, workspace)
        try:
            structurally_unreachable_mutations = load_structurally_unreachable_mutations(
                workspace,
                set(VARIANTS),
            )
        except ValueError as exc:
            failures.append(f"FAIL: structurally_unreachable data: {exc}")
            evidence.append(f"FAIL: structurally_unreachable data\n{exc}")
            save_evidence()
            print("FAIL: structurally_unreachable data")
            print(failures[-1])
            return 1

        sources = {
            ENGINE: (workspace / ENGINE).read_text(encoding="utf-8"),
            ROUTE_SOURCE: (workspace / ROUTE_SOURCE).read_text(encoding="utf-8"),
            ETA_SOURCE: (workspace / ETA_SOURCE).read_text(encoding="utf-8"),
            TURN_PASS_SOURCE: (workspace / TURN_PASS_SOURCE).read_text(encoding="utf-8"),
            MODEL_SOURCE: (workspace / MODEL_SOURCE).read_text(encoding="utf-8"),
        }
        needle_errors = mutation_needle_failures(sources)
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
            target_relative = VARIANT_SOURCE_PATHS.get(name, ENGINE)
            target = workspace / target_relative
            target_original = sources[target_relative]
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
            status, variant_failures = mutation_outcome(
                name,
                consumer_results,
                structurally_unreachable_mutations,
            )
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
            if name in structurally_unreachable_mutations:
                pair = structurally_unreachable_mutations[name]
                detail = f"pair={pair[0]}/{pair[1]}"
                if failed_tests:
                    detail += "; unexpected detection=" + "; ".join(failed_tests)
            else:
                detail = "; ".join(failed_tests)
            summary_rows.append((name, ", ".join(sorted(required)), status, detail))
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
    task080_rows = [row for row in summary_rows if row[0].startswith("task080-")]
    detected_count = sum(row[2] == "DETECTED" for row in task080_rows)
    exempt_count = sum(row[2] == "NOT-DETECTABLE (D-179)" for row in task080_rows)
    survivor_count = sum(mutation_is_survivor(row[2]) for row in task080_rows)
    print(
        "TASK-080 mutation outcomes "
        f"detected={detected_count} exempt={exempt_count} survivors={survivor_count}"
    )
    if failures:
        print("FAIL: D-033 negative control")
        print("\n".join(failures))
        return 1
    print(
        f"PASS: D-033 consumers validated {len(VARIANTS)} engine mutations "
        f"(TASK-080 detected={detected_count}, NOT-DETECTABLE={exempt_count}, survivors={survivor_count})"
    )
    print(f"evidence={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
