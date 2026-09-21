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
