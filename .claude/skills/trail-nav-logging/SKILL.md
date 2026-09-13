---
name: trail-nav-logging
description: Preserve deterministic, append-only trail navigation sessions and build failures.
---

# Trail navigation logging

- Keep the manifest immutable and events append-only.
- Keep raw inputs needed for offline replay; derive summaries at consumption time.
- The guide stream records the input snapshot, reason, and utterance for every decision.
- The engine must not read a clock, generate random values, or access I/O directly.
- Runtime uploads default to off. Do not commit measured GPX tracks or session logs to the public repository.
- Use a versioned schema and preserve original failure records; append resolutions separately.
- Credentials are the only data masked at capture time and the applied masking ruleset version is recorded.

