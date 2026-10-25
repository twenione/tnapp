#!/usr/bin/env python3
"""Assert the GPX privacy negative controls are rejected by their field kind."""
from __future__ import annotations

import unittest
from pathlib import Path

from privacy_scan import scan_session


class PrivacyGpxNegativeControlsTest(unittest.TestCase):
    def test_coordinate_elevation_and_waypoint_name_gpx_controls(self) -> None:
        root = Path(__file__).resolve().parent.parent / "testdata" / "violations"
        cases = {
            "privacy_route_gpx": "route.gpx.coordinate",
            "privacy_route_gpx_elevation": "route.gpx.elevation",
            "privacy_route_gpx_waypoint_name": "route.gpx.waypoint_name",
        }
        for directory, expected_kind in cases.items():
            findings = scan_session(root / directory)
            self.assertGreater(
                findings.get(expected_kind, 0),
                0,
                f"{directory}: expected scanner finding {expected_kind}",
            )


if __name__ == "__main__":
    unittest.main()
