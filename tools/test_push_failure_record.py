#!/usr/bin/env python3
"""Offline contract tests for the Actions-only failure-log writer."""
from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path
from unittest.mock import patch

import push_failure_record as writer

ROOT = Path(__file__).resolve().parents[1]


class FakeResponse:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return b'{"commit":{"sha":"commit-sha"}}'


def main() -> int:
    work = ROOT / "testdata" / "failure_extractor" / ".tmp-push-contract"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir()
    try:
        record = work / "record.json"
        record.write_text(json.dumps({"schema_version": "failrec-2.0.2", "run_id": "123"}), encoding="utf-8")
        old = os.environ.copy()
        os.environ.update({
            "FAILURE_LOG_TOKEN": "token",
            "GITHUB_ACTIONS": "true",
            "GITHUB_RUN_ID": "999",
            "GITHUB_REPOSITORY": "twenione/tnapp",
            "GITHUB_WORKFLOW": "preserve-failure",
        })
        requests = []

        def fake_urlopen(request, timeout=30):
            requests.append(json.loads(request.data.decode("utf-8")))
            return FakeResponse()

        with patch.object(writer.urllib.request, "urlopen", side_effect=fake_urlopen):
            assert writer.upload(record, "records/123.json", "token", "twenione/tnapp-failure-log") == 0
        payload = requests[0]
        assert payload["author"] == {"name": "tnapp-failure-recorder", "email": "failure-recorder@tnapp.invalid"}
        assert payload["committer"] == payload["author"]
        assert payload["message"].splitlines()[0] == "ci: append failrec-2.0.2 123"
        assert "Workflow: preserve-failure" in payload["message"]
        assert "Workflow-Run: https://github.com/twenione/tnapp/actions/runs/999" in payload["message"]

        requests.clear()
        os.environ.pop("GITHUB_ACTIONS", None)
        with patch.object(writer.urllib.request, "urlopen", side_effect=fake_urlopen):
            assert writer.main.__module__ == "push_failure_record"
            with patch.object(sys, "argv", ["push_failure_record.py", str(record)]):
                assert writer.main() == 2
        assert not requests
        os.environ.update(old)

        os.environ.update({"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "999", "GITHUB_REPOSITORY": "twenione/tnapp", "GITHUB_WORKFLOW": "preserve-failure"})
        assert writer.upload(record, "other/123.json", "token", "twenione/tnapp-failure-log") == 2
        print("push_contract actions_identity=PASS outside_actions=PASS allowlist=PASS")
        return 0
    finally:
        os.environ.clear()
        os.environ.update(old if "old" in locals() else {})
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
