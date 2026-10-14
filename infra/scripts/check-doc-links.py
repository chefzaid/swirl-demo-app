#!/usr/bin/env python3
"""Fail when a Markdown link points to a missing repository file or heading."""
import re
import sys
from functools import cache
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = ["*.md", "docs/**/*.md"]
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$")
EXTERNAL = re.compile(r"^[a-z][a-z0-9+.-]*:", re.IGNORECASE)


def prose_lines(path):
    """Yield (line number, text) outside fenced code blocks."""
    fenced = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if FENCE.match(line):
            fenced = not fenced
        elif not fenced:
            yield number, line


def slug(heading):
    """GitHub and GitLab heading anchor."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    text = re.sub(r"[^\w\- ]", "", text.replace("`", "").lower())
    return text.replace(" ", "-")


@cache
def anchors(path):
    counts, result = {}, set()
    for _, line in prose_lines(path):
        match = HEADING.match(line)
        if match:
            base = slug(match.group(1))
            count = counts.get(base, 0)
            counts[base] = count + 1
            result.add(f"{base}-{count}" if count else base)
    return result


def broken_links(path):
    for number, line in prose_lines(path):
        for target in LINK.findall(re.sub(r"`[^`]*`", "", line)):
            if EXTERNAL.match(target):
                continue
            file_part, _, fragment = unquote(target).partition("#")
            destination = (path.parent / file_part).resolve() if file_part else path
            if not destination.is_relative_to(ROOT) or not destination.exists():
                yield number, target, "missing file"
            elif fragment and destination.suffix == ".md" and fragment.lower() not in anchors(destination):
                yield number, target, "missing heading"


def main():
    documents = sorted({path for pattern in DOCUMENTS for path in ROOT.glob(pattern)})
    failures = [f"{path.relative_to(ROOT)}:{number}: {target} ({reason})"
                for path in documents for number, target, reason in broken_links(path)]
    print("\n".join(failures) or f"Checked links in {len(documents)} Markdown files.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
