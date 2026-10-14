#!/usr/bin/env python3
"""Exercise the Playwright image check against a temporary repository tree."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("check_playwright_images", Path(__file__).with_name("check-playwright-images.py"))
images = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(images)
DIGEST = "@sha256:" + "a" * 64


class PlaywrightImageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        images.ROOT = Path(temporary.name)
        self.write(images.LOCKFILE, json.dumps({"packages": {"node_modules/@playwright/test": {"version": "1.64.0"}}}))
        self.write(".gitlab-ci.yml", f"e2e:\n  image:\n    name: mcr.microsoft.com/playwright:v1.64.0-noble{DIGEST}\n")
        self.write("infra/compose/compose.test.yaml", "test-runner:\n  image: mcr.microsoft.com/playwright:v1.64.0-noble\n")

    def write(self, name, text):
        path = images.ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def test_accepts_images_of_the_locked_release(self):
        self.assertEqual([], list(images.mismatches()))
        self.assertEqual(0, images.main())

    def test_rejects_an_older_tag_or_an_untagged_digest(self):
        self.write(".gitlab-ci.yml", f"image: mcr.microsoft.com/playwright{DIGEST}\n")
        self.write("infra/compose/compose.test.yaml", "image: mcr.microsoft.com/playwright:v1.62.1-noble\n")
        self.assertEqual([".gitlab-ci.yml", "infra/compose/compose.test.yaml"],
                         [failure.split(":", 1)[0] for failure in images.mismatches()])
        self.assertEqual(1, images.main())

    def test_rejects_a_runner_without_a_playwright_image(self):
        self.write("infra/compose/compose.test.yaml", "test-runner:\n  image: node:24\n")
        self.assertEqual(["infra/compose/compose.test.yaml: no Playwright image found"], list(images.mismatches()))


if __name__ == "__main__":
    unittest.main()
