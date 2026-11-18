#!/usr/bin/env python3
"""Exercise the command-line prod promotion against an isolated fake GitLab API."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
TOKEN = "glpat-fixture-only-token"
API = "https://gitlab.example.test/api/v4/projects/teams%2Ftesting%2Fswirl-demo-app"
CURL = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
state_path = pathlib.Path(os.environ["PROMOTE_FIXTURE_STATE"])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
url = args[-1]
call = {"argv": args, "url": url, "method": args[args.index("--request") + 1] if "--request" in args else "GET",
        "data": json.loads(args[args.index("--data") + 1]) if "--data" in args else None}
header = pathlib.Path(args[args.index("--config") + 1]).read_text() if "--config" in args else ""
call["authorized"] = header == 'header = "PRIVATE-TOKEN: %s"\n' % state["token"]
state["calls"].append(call)

def reply(value, code=0):
    state_path.write_text(json.dumps(state))
    print(value if isinstance(value, str) else json.dumps(value))
    sys.exit(code)

if not url.startswith(state["api"]):
    reply({"message": "404 Project Not Found"}, 22)
if not call["authorized"]:
    reply({"message": "401 Unauthorized"}, 22)
path = url[len(state["api"]):]
if path == "":
    reply({"id": 735, "default_branch": "trunk"})
if path == "/pipeline":
    if state["scenario"] == "refused":
        reply({"message": {"base": ["Pipeline will not run for the selected trigger."]}}, 22)
    reply({"id": 77, "status": "created", "web_url": "https://gitlab.example.test/p/77"})
finished = state["polls"] >= 1
if path.startswith("/pipelines/77/jobs"):
    deploy = "running"
    if finished:
        deploy = "failed" if state["scenario"] == "failure" else "success"
    reply([{"id": 4, "name": "00-delivery-policy", "status": "success", "duration": 9.6, "web_url": "https://gitlab.example.test/j/4"},
           {"id": 5, "name": "02-deploy", "status": deploy, "duration": 51.2 if finished else None,
            "web_url": "https://gitlab.example.test/j/5"}])
if path == "/pipelines/77":
    status = "running"
    if finished:
        status = "failed" if state["scenario"] == "failure" else "success"
    state["polls"] += 1
    reply({"id": 77, "status": status})
if path == "/jobs/5/artifacts/release.env":
    reply("APP_VERSION=1.0.1\nDEPLOY_REVISION=" + "a" * 40 + "\nDEPLOYMENT_URL=https://demo.example.test\n")
reply({"message": "404 Not Found"}, 22)
'''


class PromoteProdTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="swirl-demo-app-promote-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "infra/scripts").mkdir(parents=True)
        shutil.copy2(ROOT / "infra/scripts/promote-prod.sh", self.root / "infra/scripts/promote-prod.sh")
        # Defaults come from the onboarding record, as in a configured copy of the template.
        (self.root / "infra/onboarding-values.json").write_text(json.dumps({"version": 1, "context": {
            "GITLAB_PUBLIC_URL": "https://gitlab.example.test", "GITLAB_PROJECT_PATH": "teams/testing/swirl-demo-app"}}))
        (self.root / "bin").mkdir()
        (self.root / "bin/curl").write_text(CURL)
        (self.root / "bin/curl").chmod(0o700)
        self.state_path = self.root / "api.json"
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GITLAB_")}
        self.env.update(PATH=str(self.root / "bin") + os.pathsep + os.environ["PATH"], GITLAB_TOKEN=TOKEN,
                        PROMOTE_FIXTURE_STATE=str(self.state_path), PROMOTE_PROD_POLL_SECONDS="0")

    def promote(self, *arguments, scenario="success", **changes):
        self.state_path.write_text(json.dumps({"api": API, "token": TOKEN, "scenario": scenario, "polls": 0, "calls": []}))
        result = subprocess.run(["bash", "infra/scripts/promote-prod.sh", *arguments], cwd=self.root,
                                env={**self.env, **changes}, text=True, capture_output=True, timeout=30)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        self.state = json.loads(self.state_path.read_text())
        for call in self.state["calls"]:
            self.assertNotIn(TOKEN, " ".join(call["argv"]))
        return result

    def test_starts_a_prod_pipeline_on_the_default_branch_and_reports_the_promoted_version(self):
        result = self.promote()
        self.assertEqual(result.returncode, 0, result.stderr)
        post = next(call for call in self.state["calls"] if call["method"] == "POST")
        self.assertEqual(post["url"], API + "/pipeline")
        self.assertEqual(post["data"], {"ref": "trunk", "variables": [
            {"key": "DEPLOYMENT_ENVIRONMENT", "value": "prod", "variable_type": "env_var"}]})
        self.assertTrue(all(call["authorized"] for call in self.state["calls"]))
        self.assertIn("https://gitlab.example.test/p/77", result.stdout)
        self.assertIn("02-deploy: running", result.stdout)
        self.assertIn("Pipeline 77: success", result.stdout)
        self.assertRegex(result.stdout, r"00-delivery-policy\tsuccess\t9s")
        self.assertRegex(result.stdout, r"02-deploy\tsuccess\t51s")
        self.assertTrue(result.stdout.rstrip().endswith("prod now runs 1.0.1 at https://demo.example.test"))

    def test_failed_promotion_names_the_failing_job_and_exits_non_zero(self):
        result = self.promote(scenario="failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Prod was not promoted", result.stderr)
        self.assertIn("02-deploy: https://gitlab.example.test/j/5", result.stderr)
        self.assertNotIn("prod now runs", result.stdout)

    def test_no_wait_only_starts_the_pipeline(self):
        result = self.promote("--no-wait")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["method"] for call in self.state["calls"]], ["GET", "POST"])
        self.assertIn("https://gitlab.example.test/p/77", result.stdout)

    def test_token_file_ref_and_overrides(self):
        token_file = self.root / "token"
        token_file.write_text(TOKEN + "\n")
        result = self.promote("--token-file", str(token_file), "--ref", "main", "--no-wait", GITLAB_TOKEN="")
        self.assertEqual(result.returncode, 0, result.stderr)
        post = next(call for call in self.state["calls"] if call["method"] == "POST")
        self.assertEqual(post["data"]["ref"], "main")
        result = self.promote("--no-wait", "--gitlab-url", "https://other.example.test", "--project", "other/app")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.state["calls"][0]["url"].startswith(
            "https://other.example.test/api/v4/projects/other%2Fapp"))

    def test_missing_or_refused_requests_fail_without_waiting(self):
        result = self.promote(GITLAB_TOKEN="")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GITLAB_TOKEN", result.stderr)
        self.assertEqual(self.state["calls"], [])
        result = self.promote(GITLAB_TOKEN="wrong-token")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.state["calls"]), 1)
        result = self.promote(scenario="refused")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("will not run for the selected trigger", result.stderr)
        self.assertFalse(any("/pipelines/" in call["url"] for call in self.state["calls"]))


if __name__ == "__main__":
    unittest.main()
