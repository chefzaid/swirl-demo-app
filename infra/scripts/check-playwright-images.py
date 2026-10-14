#!/usr/bin/env python3
"""Fail when a Playwright runner image differs from the locked @playwright/test version.

The images ship only the browser builds of their own Playwright release, so a
dependency upgrade without the matching image cannot launch any browser.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCKFILE = "swirlapp-web/package-lock.json"
RUNNERS = (".gitlab-ci.yml", "infra/compose/compose.test.yaml")
IMAGE = re.compile(r"mcr\.microsoft\.com/playwright(?::v([0-9][^\s@\"'-]*)-[a-z]+)?(@sha256:[0-9a-f]{64})?")


def locked_version():
    return json.loads((ROOT / LOCKFILE).read_text())["packages"]["node_modules/@playwright/test"]["version"]


def mismatches():
    version = locked_version()
    for name in RUNNERS:
        images = list(IMAGE.finditer((ROOT / name).read_text()))
        if not images:
            yield f"{name}: no Playwright image found"
        for image in images:
            if image.group(1) != version:
                yield f"{name}: {image.group(0)} must be the v{version} image (tag it, then pin its digest in CI)"


def main():
    failures = list(mismatches())
    print("\n".join(failures) or f"Playwright images match @playwright/test {locked_version()}.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
