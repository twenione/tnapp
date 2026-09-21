# Phase 3 design note

This note records the P3-0 decisions required before the engine PR. It follows TASK-025's recommended event shape and keeps the existing location-to-guide pairing intact.

## Q1. On-demand log and replay shape

Turn-ahead and turn-now guidance remains on the ordinary per-location `guide` stream. An on-demand request is a `sys` event with kind `ondemand.request`, `source`, and `paused` details. The response is a `guide` event with `trigger: "on-demand"`; its `src_seq` points to the last location frame used to compute the response. Replay excludes these response events from the loc↔guide count, then invokes the same pure status function at `src_seq` and compares `guidance`, `reason`, and `output_text`. A response without a matching location, or a trigger without a request, is rejected by the future session validator.

## Q2. Pure status boundary

P3-2 will expose `routeStatus(state: GuideState, config: GuideConfig): RouteStatus?` without changing state. `RouteStatus` contains `onRoute`, optional off-route distance, progress direction, remaining distance, optional `NextTurn`, and `arrived`. `NextTurn` contains the route turn index, side, remaining distance, and angle. The shared selector uses the first turn with `s > projectedMeters` for forward and stationary directions and returns no turn for reverse. The turn announcement rule and this selector will consume the same route turn index.

## Q3. Speech and ribbon correspondence

The shared status model maps to both channels:

| State | Speech items | Ribbon items |
|---|---|---|
| on-route, forward | on-route, direction, remaining distance, next turn | center/status, direction, remaining distance, next-turn marker |
| on-route, reverse | on-route, reverse, remaining distance | center/status, reverse, remaining distance |
| on-route, stationary | on-route, stopped, remaining distance, next turn | center/status, stopped, remaining distance, next-turn marker |
| off-route | off-route and distance to route | off-route band and distance marker |
| arrived | arrival only | arrival state |
| no location | location lookup only | no stale route status |

P3-4 will test that these item sets and distance strings are produced from the same `RouteStatus`.

## Q4. Media button

The service creates a `MediaSession` only after a guidance session starts, handles one `HEADSETHOOK` or `MEDIA_PLAY_PAUSE` key-down, and releases the session when the service ends. It does not claim volume keys and does not create a media playback notification. Other media apps may receive events when TrailNav is inactive; while active, the session callback is the deliberate comparison candidate for D-R6.

## Q5. Shake detector and battery measurement

P3-1 uses a pure `ShakeDetector` fed by injected timestamps and acceleration magnitudes. A hit is a magnitude over the configured threshold; two hits inside the configured window trigger one request, then the debounce interval suppresses repeats. The detector is registered only while the service runs, including screen-off operation, and is unregistered in teardown. JVM tests cover walking-like low variance, shake, threshold boundaries, and debounce. Device comparison will record start/end battery percentage, elapsed minutes, screen state, location interval, and trigger mode for matched routes; no raw sensor or location series is stored.

## Q6. Schema and compatibility

P3-1 adds no session schema fields. It records `sys.ondemand.config` and `sys.ondemand.request` through the existing system event writer; the response hook is intentionally empty until P3-4. P3-3 will add the additive `guide.trigger`/`src_seq` exception and validator rules after the engine contract is approved. Existing sessions remain valid and existing loc↔guide counts remain unchanged.

The engine, guide stream, session schema, and fixture outputs are deliberately outside this P3-0/P3-1 change. P3-2 starts only after this note is reviewed and approved by the inspection agent.

## TASK-026 P3-0b addendum

This addendum keeps Q1-Q6 above and records the revised Phase 3 numbering and Q7-Q10 decisions. Engine A/B/C are P3-2/P3-3/P3-4, tools and schema are P3-5, app integration is P3-6, final fixture regeneration is P3-7, and UX follow-up is P3-8.

### Q4 — MediaSession and battery evidence

The current P3-1 plumbing creates a `MediaSession`, sets `FLAG_HANDLES_MEDIA_BUTTONS`, installs the callback, and sets `isActive = true` only while the foreground navigation service is running. It does not claim audio focus or set a competing playback state, so it must not take over another media app. Whether Android delivers the headset button while another media app is playing is an owner-device result; the implementation will record the result after the separate wired/Bluetooth trial. This PR does not intercept volume keys.

Q5's battery plan is tied to the on-demand shake summary: the service records only per-minute maximum acceleration deviation, p95 deviation, and threshold-exceedance count in `sys` events. It never records the raw accelerometer series. The owner trial compares a three-hour baseline and a three-hour run with all Phase 3 triggers enabled on the same device, build, brightness, network, and route. The acceptance target is the existing D-041 35 percentage-point limit.

### Q7 — Dynamic guidance arbitration

Frame-derived candidates (off-route, turns, E1-E4, E6, and E7) are selected by the engine and remain ordinary `GuideResult.guidance` values. App-generated on-demand and slot events are appended to the `guide` stream with `trigger` set to `on-demand` or `slot`, `src_seq` pointing to the most recent location sequence, and an explicit suppression/configuration reason; they are excluded from loc-guide pair counts. A single priority table owns arbitration: arrival, off-route, reverse status, turn-now, turn-ahead, event candidates, then slot content. E7 is the only candidate allowed to be deferred; its pending/consumed state is held in `GuideState` and is emitted once with the current remaining-time value.

`GuideConfig` carries the periodic mode, the per-event enable flags, and the minimum event interval. Runtime changes arrive as additive configuration events, are applied to the next frame, and are recorded with the effective configuration so replay can reproduce them. The app's existing monotonic scheduler remains responsible for opening a slot; the pure `slotContent(state, config)` function chooses its content. The same table is the only place where arbitration order changes.

### Q8 — Route preprocessing shape

`RouteModel` will carry elevation samples paired with filtered route points, sanitized waypoint values with projected `s`, deterministic slope segments, and an `ElevationUse` value with one of `absent`, `partial`, `unstable`, or `ok` plus a reason. The one-metre point filter removes a route point and its elevation together, preserving index alignment. Waypoint names are trimmed, control characters removed, collapsed whitespace normalized, and capped by a `GuideConfig` length field; waypoints never enter cumulative distance, turns, or the spatial index.

The manifest records whether elevation events were used and the reason code, together with the count of eligible waypoints. A route without elevation or with partial/unstable data leaves E4 and E5 silent.

### Q9 — E4 and E7 deterministic calculations

E4 preprocessing uses a deterministic five-sample median followed by a three-sample moving average on the one-metre profile. A slope segment begins when the smoothed profile accumulates at least the configured 20 m delta inside the configured 200 m lookahead; a segment ends when the signed delta returns below the configured hysteresis. A single spike and ±3 m flat noise therefore produce no segment. Each segment is consumed once and is swapped from ascent to descent when the route is reversed.

E7 uses an offline NOAA-style solar-position calculation from UTC frame time, latitude, and longitude. The local solar date is selected after the longitude correction (`UTC + longitude / 15 hours`), then the sunset elevation is solved for the selected date. The implementation records no provider or network data. Tests cover normal latitude, polar no-sunset, already-past sunset, threshold crossing, and delayed/coalesced E7 delivery; the acceptance error is at most one minute against the deterministic reference calculation.

### Q10 — De-identification

Synthetic elevation profiles are generated from piecewise-linear ramps plus bounded deterministic noise; they are never copied from a real track. The privacy scanner gains `route.gpx.elevation` and `route.gpx.waypoint_name` field kinds, with fixtures that prove both detection and absence. Real-shape fixtures contain only synthetic-transformed `trkpt` latitude/longitude and omit `<ele>` and `<wpt>`; their manifest records elevation use as `absent`. No real elevation profile or waypoint name is stored in the repository, PR, CI log, or report.
