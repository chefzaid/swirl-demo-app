#!/usr/bin/env python3
"""Exercise the identity check against a temporary repository tree."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("check_identity", Path(__file__).with_name("check-identity.py"))
identity = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(identity)
SENTENCE = "Example is a service template for teams whose services face reviews."


class IdentityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        identity.ROOT = Path(temporary.name)
        self.write("README.md", f"# Example\n\n{SENTENCE}\n\nMore about it.\n")
        self.write("pom.xml", "<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent>"
                              "<artifactId>swirlapp-parent</artifactId>"
                              f"<description>{SENTENCE}</description>"
                              "<licenses><license><name>GPL</name></license></licenses></project>\n")
        self.write("swirlapp-web/package.json", json.dumps({"name": "web", "description": SENTENCE}))
        self.write("swirlapp-web/src/index.html", f'<head>\n  <meta name="description" content="{SENTENCE}">\n</head>\n')
        self.write("infra/scripts/configure-code-quality.sh",
                   f'APP_NAME="${{APP_NAME:-example}}"\nPROJECT_DESCRIPTION="${{PROJECT_DESCRIPTION:-{SENTENCE}}}"\n')
        self.write("infra/scripts/ci-release.sh",
                   f'jq -n --arg tag "$tag" \\\n    --arg description "{SENTENCE} Release $APP_VERSION publishes images." \\\n')

    def write(self, name, text):
        path = identity.ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def drifted(self):
        return [failure.split(":", 1)[0] for failure in identity.mismatches()]

    def test_accepts_one_sentence_repeated_everywhere(self):
        self.assertEqual([], list(identity.mismatches()))
        self.assertEqual(0, identity.main())

    def test_names_each_file_that_drifts(self):
        self.write("swirlapp-web/package.json", json.dumps({"description": "Example web"}))
        self.write("swirlapp-web/src/index.html", '<meta name="description" content="An older sentence.">\n')
        self.write("infra/scripts/ci-release.sh", '--arg description "Published images $APP_VERSION."\n')
        self.assertEqual(["swirlapp-web/package.json", "swirlapp-web/src/index.html", "infra/scripts/ci-release.sh"],
                         self.drifted())
        self.assertEqual(1, identity.main())

    def test_reads_html_entities_and_a_wrapped_readme_paragraph(self):
        self.write("README.md", "# Example\n\nExample is a service template for teams\nwhose services face reviews.\n")
        self.write("swirlapp-web/src/index.html",
                   '<meta name="description" content="Example is a service template for teams whose services face reviews&#46;">\n')
        self.assertEqual([], list(identity.mismatches()))

    def test_reports_a_missing_description(self):
        self.write("infra/scripts/configure-code-quality.sh", 'APP_NAME="${APP_NAME:-example}"\n')
        self.assertEqual(["infra/scripts/configure-code-quality.sh: no description found"], list(identity.mismatches()))

    def test_takes_the_first_sentence_of_the_opening_paragraph(self):
        self.write("README.md", f"# Example\n\n{SENTENCE}\nIt has two services.\n")
        self.assertEqual([], list(identity.mismatches()))

    def test_requires_a_plain_sentence_of_at_most_160_characters(self):
        for paragraph in ("Example is a [template](docs/x.md).", "Example is a template without a full stop",
                          "Acme's template is for teams.", "Example is a Java & Angular template.",
                          "Example is a {template}.", "Example " + "very " * 40 + "long."):
            with self.subTest(paragraph=paragraph):
                self.write("README.md", f"# Example\n\n{paragraph}\n")
                self.assertIn("README.md", self.drifted())


if __name__ == "__main__":
    unittest.main()
