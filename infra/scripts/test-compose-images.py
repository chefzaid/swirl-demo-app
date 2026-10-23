#!/usr/bin/env python3
"""Exercise the Compose image check against a temporary repository tree."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("check_compose_images", Path(__file__).with_name("check-compose-images.py"))
images = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(images)
DIGEST = "@sha256:" + "a" * 64


class ComposeImageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        images.ROOT = Path(temporary.name)
        self.write("infra/compose/compose.yaml",
                   "services:\n  postgres:\n    image: postgres:18.4-alpine\n"
                   "  keycloak:\n    image: \"quay.io/keycloak/keycloak:26.7.1\"\n"
                   "  web:\n    build: ../../swirlapp-web\n")
        self.write(".devcontainer/docker-compose.yml",
                   "services:\n  postgres:\n    image: postgres:18.4-alpine\n"
                   "  keycloak:\n    image: quay.io/keycloak/keycloak:26.7.1\n"
                   "  workspace:\n    image: mcr.microsoft.com/devcontainers/base:ubuntu\n")

    def write(self, name, text):
        path = images.ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def test_accepts_matching_images_and_ignores_unshared_ones(self):
        self.assertEqual([], list(images.mismatches()))
        self.assertEqual(0, images.main())

    def test_rejects_a_different_tag_or_digest(self):
        self.write(".devcontainer/docker-compose.yml",
                   "services:\n  postgres:\n    image: postgres:18.4-alpine" + DIGEST + "\n"
                   "  keycloak:\n    image: quay.io/keycloak/keycloak:26.7.0\n")
        self.assertEqual(["postgres", "quay.io/keycloak/keycloak"],
                         [failure.split(":", 1)[0] for failure in images.mismatches()])
        self.assertEqual(1, images.main())

    def test_treats_an_untagged_image_as_latest(self):
        self.write(".devcontainer/docker-compose.yml", "services:\n  postgres:\n    image: postgres\n")
        self.assertEqual(["postgres: infra/compose/compose.yaml pins 18.4-alpine but "
                          ".devcontainer/docker-compose.yml pins latest"], list(images.mismatches()))

    def test_rejects_a_file_without_images(self):
        self.write(".devcontainer/docker-compose.yml", "services:\n  workspace:\n    build: .\n")
        self.assertEqual([".devcontainer/docker-compose.yml: no images found"], list(images.mismatches()))


if __name__ == "__main__":
    unittest.main()
