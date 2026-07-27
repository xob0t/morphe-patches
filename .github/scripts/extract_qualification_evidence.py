#!/usr/bin/env python3
"""Extract base64-encoded qualification evidence from a promotion PR body."""

from __future__ import annotations

import argparse
import base64
import os
import re
from pathlib import Path


MARKER_RE = re.compile(
    r"<!--\s*qualification-evidence-base64:\s*([A-Za-z0-9+/=]+)\s*-->",
    re.MULTILINE,
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    body = os.environ.get("PR_BODY", "")
    matches = MARKER_RE.findall(body)
    if len(matches) != 1:
        raise SystemExit(
            f"Expected exactly one qualification evidence marker, found {len(matches)}"
        )
    try:
        evidence = base64.b64decode(matches[0], validate=True)
    except ValueError as exc:
        raise SystemExit(f"Invalid base64 qualification evidence: {exc}") from exc
    args.output.write_bytes(evidence)


if __name__ == "__main__":
    main()
