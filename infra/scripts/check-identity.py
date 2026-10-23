#!/usr/bin/env python3
"""Fail when a surface that describes the repository drifts from the README's first sentence.

The README's first paragraph is the identity sentence. Build metadata, the web
page description, the GitLab project description and the release description
repeat it, so an adopter replaces one sentence and this check finds the copies.
"""
import html
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAX_LENGTH = 160
# Markdown, and characters a shell default, JSON, XML or HTML copy would need escaped.
UNSAFE = re.compile(r"[`*_\[\]<>#|\"'&{}$\\]")


def identity():
    """The first sentence of the paragraph after the README's title, with its line breaks joined."""
    text = (ROOT / "README.md").read_text()
    match = re.search(r"^# [^\n]+\n+(.+?)(?:\n\n|\Z)", text, re.DOTALL | re.MULTILINE)
    paragraph = " ".join(match.group(1).split()) if match else ""
    return re.split(r"(?<=[.!?])\s", paragraph, maxsplit=1)[0]


def identity_problems(sentence):
    if not sentence:
        yield "README.md: no paragraph after the title"
        return
    if len(sentence) > MAX_LENGTH:
        yield f"README.md: the first sentence has {len(sentence)} characters; keep it to {MAX_LENGTH}"
    if UNSAFE.search(sentence):
        yield "README.md: the first sentence must be plain text without Markdown, quotes, braces, &, $ or backslashes"
    if not sentence.endswith("."):
        yield "README.md: the first sentence must end with a full stop"


def first(pattern, name):
    match = re.search(pattern, (ROOT / name).read_text(), re.DOTALL | re.MULTILINE)
    return match.group(1) if match else None


def surfaces():
    """Yield (file, description found there) for every copy of the identity sentence."""
    description = first(r"<artifactId>swirlapp-parent</artifactId>.*?<description>(.*?)</description>", "pom.xml")
    yield "pom.xml", None if description is None else html.unescape(description)
    yield "swirlapp-web/package.json", json.loads((ROOT / "swirlapp-web/package.json").read_text()).get("description")
    content = first(r'<meta\s+name="description"\s+content="([^"]*)"', "swirlapp-web/src/index.html")
    yield "swirlapp-web/src/index.html", None if content is None else html.unescape(content)
    yield "infra/scripts/configure-code-quality.sh", first(
        r'^PROJECT_DESCRIPTION="\$\{PROJECT_DESCRIPTION:-([^}]*)\}"$', "infra/scripts/configure-code-quality.sh")
    yield "infra/scripts/ci-release.sh", first(r'--arg description "([^"]*)"', "infra/scripts/ci-release.sh")


def mismatches():
    sentence = identity()
    yield from identity_problems(sentence)
    for name, found in surfaces():
        if found is None:
            yield f"{name}: no description found"
        elif name == "infra/scripts/ci-release.sh":
            if not found.startswith(sentence + " "):
                yield f"{name}: the release description must start with the README's first sentence"
        elif found != sentence:
            yield f"{name}: description {found!r} differs from the README's first sentence"


def main():
    failures = list(mismatches())
    print("\n".join(failures) or f"Every identity surface matches: {identity()}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
