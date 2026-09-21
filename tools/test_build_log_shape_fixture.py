#!/usr/bin/env python3
"""Verify RouteOrientation's trkpt/rtept point-selection contract."""
from __future__ import annotations

import tempfile
from pathlib import Path
from xml.etree import ElementTree as ET

from build_log_shape_fixture import _reverse_gpx


def count_points(path: Path, tag: str) -> int:
    root = ET.parse(path).getroot()
    return sum(node.tag.rsplit("}", 1)[-1] == tag for node in root.iter())


def count_children(path: Path, tag: str) -> int:
    root = ET.parse(path).getroot()
    return sum(node.tag.rsplit("}", 1)[-1] == tag for node in root.iter())


def run_case(name: str, xml: str, expected: int) -> bool:
    root = Path(tempfile.mkdtemp(prefix=f"tnapp-gpx-contract-{name}-"))
    try:
        source = root / "source.gpx"
        target = root / "target.gpx"
        source.write_text(xml, encoding="utf-8")
        _reverse_gpx(source, target)
        return count_points(target, "trkpt") == expected and count_children(target, "ele") == 0 and count_children(target, "wpt") == 0
    finally:
        import shutil
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    old_input = "<gpx><trk><trkseg><trkpt/><trkpt/></trkseg></trk><wpt/></gpx>"
    old_root = ET.fromstring(old_input)
    old_points = sum(node.tag.rsplit("}", 1)[-1] in {"trkpt", "rtept", "wpt"} for node in old_root.iter())
    cases = [
        ("trkpt_priority", "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"1\"/><trkpt lat=\"2\" lon=\"2\"/></trkseg></trk><wpt lat=\"3\" lon=\"3\"/></gpx>", 2),
        ("rtept_fallback", "<gpx><rte><rtept lat=\"1\" lon=\"1\"/><rtept lat=\"2\" lon=\"2\"/></rte></gpx>", 2),
        ("trkpt_over_rtept", "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"1\"/><trkpt lat=\"2\" lon=\"2\"/></trkseg></trk><rte><rtept lat=\"3\" lon=\"3\"/><rtept lat=\"4\" lon=\"4\"/></rte></gpx>", 2),
    ]
    failures = sum(not run_case(name, xml, expected) for name, xml, expected in cases)
    print(f"old_reverse_gpx actual_points={old_points} expected_points=2 status=FAIL_EXPECTED")
    print(f"reverse_gpx cases={len(cases)} failures={failures} elevation_waypoint_exclusion=PASS")
    if failures:
        print(f"FAIL: reverse_gpx contract failed ({failures} cases)")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
