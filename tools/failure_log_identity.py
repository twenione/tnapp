"""Identity shared by the trusted failure-log writers."""

RECORDER_NAME = "tnapp-failure-recorder"
RECORDER_EMAIL = "failure-recorder@tnapp.invalid"
ALLOWED_WRITE_PREFIXES = ("records/", "resolutions/")


def allowed_record_path(path: str) -> bool:
    return any(path.startswith(prefix) and path.endswith(".json") for prefix in ALLOWED_WRITE_PREFIXES)
