#!/usr/bin/env python3
"""Append one exact AppTarget to a mapped app compatibility declaration."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


APPS = {
    "avito": {
        "package": "com.avito.android",
        "path": "patches/src/main/kotlin/app/avito/patches/shared/Constants.kt",
    },
    "tbank": {
        "package": "com.idamob.tinkoff.android",
        "path": "patches/src/main/kotlin/app/tbank/patches/shared/Constants.kt",
    },
    "ozon": {
        "package": "ru.ozon.app.android",
        "path": "patches/src/main/kotlin/app/ozon/patches/shared/Constants.kt",
    },
    "wildberries": {
        "package": "com.wildberries.ru",
        "path": "patches/src/main/kotlin/app/wildberries/patches/shared/Constants.kt",
    },
}

VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._+-]*$")
TARGET_RE = re.compile(
    r"""AppTarget\(\s*
        version\s*=\s*"(?P<version>[^"]+)",\s*
        versionCode\s*=\s*(?P<version_code>[0-9]+),\s*
        minSdk\s*=\s*(?P<min_sdk>[0-9]+),\s*
        \)""",
    re.VERBOSE,
)
TARGET_LIST_MARKER = "        targets = listOf("


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", required=True, choices=sorted(APPS))
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    return parser.parse_args()


def read_preserving_newlines(path: Path) -> tuple[str, str]:
    raw = path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    return raw.decode("utf-8").replace("\r\n", "\n"), newline


def main() -> None:
    args = parse_args()
    app = APPS[args.app]
    if args.package != app["package"]:
        raise SystemExit(
            f"Package {args.package!r} does not match mapped package {app['package']!r}"
        )
    if not VERSION_RE.fullmatch(args.version):
        raise SystemExit(f"Unsafe version string: {args.version!r}")
    if args.version_code <= 0:
        raise SystemExit("versionCode must be positive")

    path = Path(app["path"])
    text, newline = read_preserving_newlines(path)
    targets = [
        {
            "version": match.group("version"),
            "version_code": int(match.group("version_code")),
            "min_sdk": int(match.group("min_sdk")),
        }
        for match in TARGET_RE.finditer(text)
    ]
    if not targets:
        raise SystemExit(f"No exact AppTarget blocks found in {path}")

    for target in targets:
        same_version = target["version"] == args.version
        same_code = target["version_code"] == args.version_code
        if same_version and same_code:
            print(
                json.dumps(
                    {
                        "status": "already-present",
                        "app": args.app,
                        "path": str(path),
                        "version": args.version,
                        "version_code": args.version_code,
                    }
                )
            )
            return
        if same_version or same_code:
            raise SystemExit(
                "Target collision: version and versionCode must both be unique "
                f"(existing {target['version']} / {target['version_code']})"
            )

    marker_index = text.find(TARGET_LIST_MARKER)
    if marker_index < 0 or text.find(TARGET_LIST_MARKER, marker_index + 1) >= 0:
        raise SystemExit(f"Expected exactly one target list marker in {path}")
    insertion_index = marker_index + len(TARGET_LIST_MARKER)
    min_sdk = targets[0]["min_sdk"]
    block = (
        "\n"
        "            AppTarget(\n"
        f'                version = "{args.version}",\n'
        f"                versionCode = {args.version_code},\n"
        f"                minSdk = {min_sdk},\n"
        "            ),"
    )
    updated = text[:insertion_index] + block + text[insertion_index:]
    path.write_bytes(updated.replace("\n", newline).encode("utf-8"))
    print(
        json.dumps(
            {
                "status": "appended",
                "app": args.app,
                "path": str(path),
                "version": args.version,
                "version_code": args.version_code,
                "min_sdk": min_sdk,
            }
        )
    )


if __name__ == "__main__":
    main()
