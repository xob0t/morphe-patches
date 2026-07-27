#!/usr/bin/env python3
"""Validate a target-promotion PR against attested qualification evidence."""

from __future__ import annotations

import argparse
import collections
import json
import re
import subprocess
from pathlib import Path

from append_app_target import APPS, TARGET_RE


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
AUTOBUILD_REPOSITORY = "xob0t/morphe-autobuilds"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def parse_targets(text: str) -> list[tuple[str, int, int]]:
    return [
        (
            match.group("version"),
            int(match.group("version_code")),
            int(match.group("min_sdk")),
        )
        for match in TARGET_RE.finditer(text)
    ]


def load_base_file(base: str, path: str) -> str:
    return git("show", f"{base}:{path}")


def validate_evidence(evidence: dict[str, object]) -> None:
    required_strings = (
        "app",
        "package",
        "version_name",
        "source",
        "apk_sha256",
        "patch_tag",
        "patch_bundle_sha256",
        "run_url",
        "autobuild_repository",
    )
    for key in required_strings:
        if not isinstance(evidence.get(key), str) or not evidence[key]:
            raise SystemExit(f"Evidence field {key!r} must be a non-empty string")
    if evidence["autobuild_repository"] != AUTOBUILD_REPOSITORY:
        raise SystemExit("Evidence came from an unexpected autobuild repository")
    if not SHA256_RE.fullmatch(str(evidence["apk_sha256"])):
        raise SystemExit("Invalid APK SHA-256 in evidence")
    if not SHA256_RE.fullmatch(str(evidence["patch_bundle_sha256"])):
        raise SystemExit("Invalid patch bundle SHA-256 in evidence")
    if not isinstance(evidence.get("version_code"), int) or evidence["version_code"] <= 0:
        raise SystemExit("Evidence version_code must be a positive integer")
    if evidence.get("qualification") is not True:
        raise SystemExit("Evidence is not a successful qualification")
    selected = evidence.get("selected_patches")
    applied = evidence.get("applied_patches")
    if not isinstance(selected, list) or not selected:
        raise SystemExit("Evidence selected_patches must be a non-empty list")
    if not isinstance(applied, list):
        raise SystemExit("Evidence applied_patches must be a list")
    if collections.Counter(selected) != collections.Counter(applied):
        raise SystemExit("Evidence selected/applied patch multisets differ")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()

    evidence = json.loads(args.evidence.read_text(encoding="utf-8"))
    validate_evidence(evidence)
    app_id = str(evidence["app"])
    if app_id not in APPS:
        raise SystemExit(f"Unknown app in evidence: {app_id}")
    app = APPS[app_id]
    if evidence["package"] != app["package"]:
        raise SystemExit("Evidence package does not match mapped app")

    changed_files = [
        line for line in git("diff", "--name-only", f"{args.base}...HEAD").splitlines() if line
    ]
    expected_path = app["path"]
    if changed_files != [expected_path]:
        raise SystemExit(
            f"Promotion must change only {expected_path}; changed: {changed_files}"
        )

    base_targets = parse_targets(load_base_file(args.base, expected_path))
    head_targets = parse_targets(Path(expected_path).read_text(encoding="utf-8"))
    if len(head_targets) != len(base_targets) + 1:
        raise SystemExit("Promotion must append exactly one AppTarget")
    new_target = (
        str(evidence["version_name"]),
        int(evidence["version_code"]),
        base_targets[0][2],
    )
    if head_targets != [new_target, *base_targets]:
        raise SystemExit(
            "Promotion must prepend exactly the qualified target without changing "
            "or reordering existing targets"
        )

    versions = [target[0] for target in head_targets]
    version_codes = [target[1] for target in head_targets]
    if len(versions) != len(set(versions)):
        raise SystemExit("Target versions must be unique")
    if len(version_codes) != len(set(version_codes)):
        raise SystemExit("Target versionCodes must be unique")

    print(
        json.dumps(
            {
                "status": "valid",
                "app": app_id,
                "target": {
                    "version": evidence["version_name"],
                    "version_code": evidence["version_code"],
                },
            }
        )
    )


if __name__ == "__main__":
    main()
