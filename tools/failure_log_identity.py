"""Identity shared by the trusted failure-log writers."""

RECORDER_NAME = "tnapp-failure-recorder"
RECORDER_EMAIL = "failure-recorder@tnapp.invalid"
from pathlib import PurePosixPath


def allowed_record_path(path: str) -> bool:
    parts = PurePosixPath(path).parts
    return len(parts) == 2 and parts[0] in {"records", "resolutions"} and parts[1].endswith(".json")
