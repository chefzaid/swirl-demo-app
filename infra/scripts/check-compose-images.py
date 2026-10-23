#!/usr/bin/env python3
"""Fail when the dev container and the Compose stack pin different backing-service images.

Both files start PostgreSQL, Redis, Kafka and Keycloak; a version that differs
between them means the dev container and the acceptance stack test different
services.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FILES = ("infra/compose/compose.yaml", ".devcontainer/docker-compose.yml")
IMAGE = re.compile(r"^\s*image:\s*[\"']?([^\s\"'@]+?)(?::([^\s\"'@/]+))?(@sha256:[0-9a-f]{64})?[\"']?\s*$", re.MULTILINE)


def images(name):
    return {match.group(1): (match.group(2) or "latest") + (match.group(3) or "")
            for match in IMAGE.finditer((ROOT / name).read_text())}


def mismatches():
    pinned = {name: images(name) for name in FILES}
    for name, found in pinned.items():
        if not found:
            yield f"{name}: no images found"
    first, second = (pinned[name] for name in FILES)
    for repository in sorted(first.keys() & second.keys()):
        if first[repository] != second[repository]:
            yield (f"{repository}: {FILES[0]} pins {first[repository]} but "
                   f"{FILES[1]} pins {second[repository]}")


def main():
    failures = list(mismatches())
    print("\n".join(failures) or "Compose and dev container images match.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
