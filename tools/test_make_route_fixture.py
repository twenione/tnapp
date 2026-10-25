#!/usr/bin/env python3
"""Regression tests for anonymized, real-route-shape fixture generation."""
from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

import make_route_fixture as fixture


SAMPLE_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="private-device" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>private route label</name><time>2026-10-25T01:02:03Z</time></metadata>
  <wpt lat="37.10000000" lon="127.10000000"><name>private waypoint</name></wpt>
  <trk><name>private track title</name><trkseg>
    <trkpt lat="37.10000000" lon="127.10000000"><ele>101.25</ele><time>2026-10-25T01:02:03Z</time></trkpt>
    <trkpt lat="37.10005000" lon="127.10006000"><ele>109.75</ele></trkpt>
    <trkpt lat="37.10010000" lon="127.10012000"><ele>105.50</ele></trkpt>
    <trkpt lat="37.10015000" lon="127.10018000"><ele>122.00</ele></trkpt>
  </trkseg></trk>
</gpx>
"""


class MakeRouteFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "source.gpx"
        self.source.write_text(SAMPLE_GPX, encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_repeated_runs_randomize_coordinates_and_preserve_shape(self) -> None:
        parsed, _ = fixture._read_route(self.source)
        original_points, original_cumulative = fixture._filtered_route(parsed)
        original_deltas = [
            (float(b["lat"]) - float(a["lat"]), float(b["lon"]) - float(a["lon"]))
            for a, b in zip(original_points, original_points[1:])
        ]
        original_elevation_deltas = [
            float(b["elevation"]) - float(a["elevation"])
            for a, b in zip(original_points, original_points[1:])
        ]
        with tempfile.TemporaryDirectory(dir=self.root) as first_dir, tempfile.TemporaryDirectory(dir=self.root) as second_dir:
            first = Path(first_dir) / "fixture"
            second = Path(second_dir) / "fixture"
            fixture.make_fixture(self.source, 0.0, 20.0, first)
            fixture.make_fixture(self.source, 0.0, 20.0, second)
            first_points, _ = fixture._read_route(first / "route.gpx")
            second_points, _ = fixture._read_route(second / "route.gpx")
            first_points, first_cumulative = fixture._filtered_route(first_points)
            second_points, _ = fixture._filtered_route(second_points)
            self.assertEqual(len(original_points), len(first_points))
            self.assertEqual(len(first_points), len(second_points))
            first_deltas = [
                (float(b["lat"]) - float(a["lat"]), float(b["lon"]) - float(a["lon"]))
                for a, b in zip(first_points, first_points[1:])
            ]
            for before, after in zip(original_deltas, first_deltas):
                self.assertAlmostEqual(before[0], after[0], delta=2.1e-8)
                self.assertAlmostEqual(before[1], after[1], delta=2.1e-8)
            original_intervals = [b - a for a, b in zip(original_cumulative, original_cumulative[1:])]
            output_intervals = [b - a for a, b in zip(first_cumulative, first_cumulative[1:])]
            for before, after in zip(original_intervals, output_intervals):
                self.assertAlmostEqual(before, after, delta=0.02)
            first_elevation_deltas = [
                float(b["elevation"]) - float(a["elevation"])
                for a, b in zip(first_points, first_points[1:])
            ]
            for before, after in zip(original_elevation_deltas, first_elevation_deltas):
                self.assertAlmostEqual(before, after, delta=1e-7)
            self.assertNotEqual(
                [(point["lat"], point["lon"]) for point in first_points],
                [(point["lat"], point["lon"]) for point in second_points],
            )
            self.assertGreater(first_cumulative[-1], 0.0)

    def test_metadata_waypoints_names_times_and_source_scalars_are_removed(self) -> None:
        source_points, _ = fixture._read_route(self.source)
        with tempfile.TemporaryDirectory(dir=self.root) as output_dir:
            output = Path(output_dir) / "fixture"
            fixture.make_fixture(self.source, 0.0, 20.0, output)
            raw = (output / "route.gpx").read_bytes()
            root = ET.fromstring(raw)
            tags = {fixture._local_name(node.tag) for node in root.iter()}
            self.assertFalse(tags & {"name", "time", "metadata", "extensions", "wpt", "rtept"})
            fixture._validate_output(raw, source_points)
            self.assertIn('"elevation_source": "synthetic"', (output / "manifest.json").read_text(encoding="utf-8"))

    def test_cli_does_not_log_or_store_offsets(self) -> None:
        output = self.root / "fixture"
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(fixture, "_pick_offsets", return_value=(0.000137, 45.6789, 12_345.6789)):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = fixture.main([
                    "--source", str(self.source), "--start-m", "0", "--end-m", "20", "--out", str(output),
                ])
        self.assertEqual(0, result)
        self.assertNotIn("0.000137", stdout.getvalue() + stderr.getvalue())
        self.assertNotIn("45.6789", stdout.getvalue() + stderr.getvalue())
        self.assertNotIn("12345.6789", stdout.getvalue() + stderr.getvalue())
        root = ET.parse(output / "route.gpx").getroot()
        self.assertFalse(any("offset" in fixture._local_name(node.tag).lower() for node in root.iter()))

    def test_unchanged_coordinate_negative_control_is_rejected(self) -> None:
        points, _ = fixture._read_route(self.source)
        unchanged = fixture._build_xml(points, (0.0, 0.0, 12_345.0))
        with self.assertRaisesRegex(ValueError, "source coordinate scalar remains"):
            fixture._validate_output(unchanged, points)


if __name__ == "__main__":
    unittest.main()
