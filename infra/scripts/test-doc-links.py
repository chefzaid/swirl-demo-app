#!/usr/bin/env python3
"""Exercise the Markdown link checker against a temporary documentation tree."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("check_doc_links", Path(__file__).with_name("check-doc-links.py"))
links = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(links)


class DocLinkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        links.ROOT = Path(self.temporary.name).resolve()
        links.anchors.cache_clear()
        (links.ROOT / "docs").mkdir()
        self.write("docs/guide.md", "# Guide\n\n## Add `kubectl` Access\n\n## Steps\n\n## Steps\n")

    def write(self, name, text):
        path = links.ROOT / name
        path.write_text(text, encoding="utf-8")
        return path

    def broken(self, text):
        return [target for _, target, _ in links.broken_links(self.write("README.md", text))]

    def test_accepts_existing_files_and_headings(self):
        self.assertEqual([], self.broken(
            "[a](docs/guide.md) [b](docs/guide.md#add-kubectl-access) [c](docs/guide.md#steps-1) "
            "[d](#readme) [e](https://example.test/missing)\n\n# Readme\n"))

    def test_reports_missing_files_headings_and_escapes(self):
        self.assertEqual(["docs/missing.md", "docs/guide.md#missing", "../outside.md"], self.broken(
            "[a](docs/missing.md) [b](docs/guide.md#missing) [c](../outside.md)\n"))

    def test_ignores_code(self):
        self.assertEqual([], self.broken("`[a](missing.md)`\n\n```\n[b](missing.md)\n```\n"))


if __name__ == "__main__":
    unittest.main()
