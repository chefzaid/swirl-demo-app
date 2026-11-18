#!/usr/bin/env python3
"""Exercise the app's declarative onboarding configuration without live services."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / "infra/onboarding.json").read_text())
APP = CONTRACT["registry"]["path"].split("/")[1]
PLATFORM = None
if os.environ.get("SWIRL_CLOUD_SOURCE"):
    sys.path.insert(0, str(Path(os.environ["SWIRL_CLOUD_SOURCE"]) / "scripts/lib"))
    import repository_onboarding as PLATFORM


def command(args, cwd, *, env=None, check=True):
    return subprocess.run(args, cwd=cwd, env=env, check=check, text=True,
                          capture_output=True, timeout=60)


def expand(value, context):
    return re.sub(r"\{\{([A-Z_]+)\}\}", lambda match: context[match[1]], value)


def render_fixture(root, context):
    state_path = root / "infra/onboarding-values.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    if PLATFORM:
        PLATFORM.render(root, PLATFORM.validate_contract(root), context, state)
        return
    previous = state.get("bindings", {})
    bindings = {item["from"]: expand(item["to"], context) for item in CONTRACT["replacements"]}
    replacements = {}
    for original, rendered in bindings.items():
        old = previous.get(original, original)
        if old in replacements and replacements[old] != rendered:
            raise AssertionError("Conflicting previous onboarding bindings")
        replacements[old] = rendered
    pattern = re.compile("|".join(re.escape(key) for key in sorted(replacements, key=len, reverse=True)))
    for name in CONTRACT["files"]:
        path = root / name
        path.write_text(pattern.sub(lambda match: replacements[match[0]], path.read_text()))
    # The platform owns these structured Argo fields, independent of replacements.
    application = root / CONTRACT["application"]
    app = yaml.safe_load(application.read_text())
    app["spec"]["source"].update(repoURL=context["GITLAB_REPOSITORY_URL"],
                                  targetRevision=context["DEFAULT_BRANCH"])
    application.write_text(yaml.safe_dump(app, sort_keys=False))
    state_path.write_text(json.dumps({"version": 1, "context": context, "bindings": bindings}))


def rendered_resources(root, profile="infra/k8s"):
    return list(yaml.safe_load_all(command(["kubectl", "kustomize", profile], root).stdout))


def job_rule(job, variables):
    rules = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())[job]["rules"]
    known = {name: "" for rule in rules for name in re.findall(r"\$([A-Z_]+)", rule["if"])}
    for rule in rules:
        result = subprocess.run(["bash", "-c", "[[ " + rule["if"] + " ]]"],
                                env={**os.environ, **known, **variables}, capture_output=True)
        if result.returncode == 0:
            return rule.get("when", "on_success")
    return "absent"


def release_rule(variables):
    return job_rule("01-release", variables)


class RenderingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix=APP + "-onboarding-render-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(ROOT / "infra", self.root / "infra", ignore=shutil.ignore_patterns("__pycache__"))
        for name in CONTRACT["files"]:
            source = ROOT / name
            self.assertTrue(source.is_file(), name)
            self.assertFalse(source.is_symlink(), name)
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        self.context = {
            "PUBLIC_DOMAIN": "example.test", "INTERNAL_DNS_ZONE": "services.test",
            "POD_CIDR": "10.60.0.0/16",
            "APP_SUBDOMAIN": "portal", "APP_HOST": "portal.example.test",
            "TLS_SECRET_NAME": "example-test-tls",
            "GITLAB_PROJECT_PATH": "teams/testing/" + APP + "-copy", "GITLAB_PROJECT_ID": "735",
            "GITLAB_PUBLIC_URL": "https://source.example.test",
            "GITLAB_INTERNAL_URL": "http://gitlab.services.test",
            "GITLAB_REPOSITORY_URL": "http://gitlab.services.test/teams/testing/" + APP + "-copy.git",
            "REGISTRY_HOST": "registry.example.test", "REGISTRY_PUSH_HOST": "gitlab-registry.services.test:5050",
            "GITHUB_OWNER": "example-org", "GITHUB_REPOSITORY": APP + "-copy",
            "DEFAULT_BRANCH": "trunk", "KEYCLOAK_REALM": "people",
            "SONAR_PROJECT_KEY": "teams:testing:" + APP + "-copy",
        }

    def assert_configuration(self, context):
        resources = rendered_resources(self.root)
        configs = {r["metadata"]["name"]: r for r in resources if r["kind"] == "ConfigMap"}
        backend = next(r for name, r in configs.items() if name.startswith("swirl-demo-app-backend-config-"))
        self.assertEqual(backend["data"]["DB_HOST"], "postgres." + context["INTERNAL_DNS_ZONE"])
        source = yaml.safe_load((self.root / CONTRACT["application"]).read_text())["spec"]["source"]
        self.assertEqual(source["targetRevision"], context["DEFAULT_BRANCH"])
        self.assertEqual(source["repoURL"], context["GITLAB_REPOSITORY_URL"])
        self.assertIn(context["SONAR_PROJECT_KEY"], (self.root / "sonar-project.properties").read_text())
        ci = yaml.safe_load((self.root / ".gitlab-ci.yml").read_text())
        self.assertEqual(ci["variables"]["REGISTRY_PUSH_HOST"], context["REGISTRY_PUSH_HOST"])
        self.assertEqual(ci["variables"]["DEPLOYMENT_ENVIRONMENT"]["options"], ["int", "uat", "prod"])
        self.assertEqual(ci["variables"]["DEPLOYMENT_ENVIRONMENT"]["value"], "int")
        client = json.loads((self.root / CONTRACT["keycloak"]["file"]).read_text())
        self.assertEqual(client["webOrigins"], ["https://" + context["APP_HOST"]])
        self.assertEqual(client["redirectUris"], ["https://" + context["APP_HOST"] + "/*"])
        self.assertEqual(client["attributes"]["pkce.code.challenge.method"], "S256")
        self.assertFalse(any(r["kind"] == "Job" and r["metadata"]["name"] == "swirl-demo-app-db-setup" for r in resources))
        rendered_resources(self.root, "infra/overlays/ha")

    def test_shared_settings_leave_source_and_environment_selection_independent(self):
        self.assertEqual(CONTRACT["version"], 2)
        self.assertEqual(CONTRACT["deployment"]["defaultEnvironment"], "int")
        self.assertFalse(any("/src/" in name or name.endswith("nginx.conf") for name in CONTRACT["files"]))
        self.assertFalse(any("DEPLOYMENT_ENVIRONMENT" in b["to"] for b in CONTRACT["replacements"]))
        source_files = [p for base in ("swirlapp-web/src", "swirlapp-common/src", "swirlapp-user/src", "swirlapp-order/src")
                        for p in (ROOT / base).rglob("*") if p.is_file()]
        for path in source_files:
            target = self.root / path.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        render_fixture(self.root, self.context)
        self.assert_configuration(self.context)
        for path in source_files:
            self.assertEqual(path.read_bytes(), (self.root / path.relative_to(ROOT)).read_bytes(), str(path))

    def test_rerun_changed_domain_and_branch_remain_structured_shared_settings(self):
        render_fixture(self.root, self.context)
        snapshot = {name: (self.root / name).read_bytes() for name in CONTRACT["files"]}
        render_fixture(self.root, self.context)
        self.assertEqual(snapshot, {name: (self.root / name).read_bytes() for name in CONTRACT["files"]})
        for branch in ("no", "true", "null", "123", "release/stable", "team's-release"):
            changed = dict(self.context, DEFAULT_BRANCH=branch, PUBLIC_DOMAIN="second.test",
                           APP_HOST="renamed.second.test", APP_SUBDOMAIN="renamed", KEYCLOAK_REALM="team")
            render_fixture(self.root, changed)
            self.assert_configuration(changed)

    def test_apex_and_subdomain_changes_preserve_shared_registry_identity(self):
        for label in ("@", "renamed"):
            changed = dict(self.context, APP_SUBDOMAIN=label,
                           APP_HOST=self.context["PUBLIC_DOMAIN"] if label == "@" else label + "." + self.context["PUBLIC_DOMAIN"])
            render_fixture(self.root, changed)
            self.assert_configuration(changed)
        self.assertEqual(CONTRACT["registry"]["path"], "apps/swirl-demo-app/registry")


class ReleaseTests(unittest.TestCase):
    def test_publication_commit_marks_only_onboarding_and_supports_same_image(self):
        with tempfile.TemporaryDirectory(prefix="onboarding-commit-test.") as directory:
            work = Path(directory)
            def run(*arguments, environment=None, check=True):
                return subprocess.run(arguments, cwd=work, env=environment, check=check,
                                      text=True, capture_output=True)
            run("git", "init", "-q", "-b", "main")
            run("git", "config", "user.name", "Onboarding test")
            run("git", "config", "user.email", "test@example.invalid")
            run("git", "config", "commit.gpgsign", "false")
            (work / "config").write_text("public settings")
            run("git", "add", ".")
            helper = str(ROOT / "infra/scripts/commit-deployment.sh")
            ordinary = {**os.environ, "APP_ONBOARDING": "false"}
            run("bash", helper, "ordinary deployment", environment=ordinary)
            initial = run("git", "rev-parse", "HEAD").stdout.strip()
            self.assertNotIn("Onboarding-", run("git", "show", "-s", "--format=%B").stdout)
            environment = {**os.environ, "APP_ONBOARDING": "true", "CI_PIPELINE_ID": "73", "CI_COMMIT_SHA": initial}
            # Even an unchanged image gets an identifiable successful publication.
            run("bash", helper, "onboarding deployment", environment=environment)
            published = run("git", "rev-parse", "HEAD").stdout.strip()
            self.assertNotEqual(published, initial)
            trailers = run("git", "show", "-s", "--format=%(trailers)").stdout
            self.assertEqual(trailers.splitlines(), ["Onboarding-Pipeline: 73", "Onboarding-Source: " + initial, ""])
            result = run("bash", helper, "invalid metadata", environment={**environment, "CI_PIPELINE_ID": ""}, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(run("git", "rev-parse", "HEAD").stdout.strip(), published)

    def test_only_explicit_api_onboarding_bypasses_manual_release(self):
        defaults = {"CI_PIPELINE_SOURCE": "api", "CI_COMMIT_BRANCH": "trunk",
                    "CI_DEFAULT_BRANCH": "trunk", "APP_ONBOARDING": "true",
                    "SONAR_SCAN_ONLY": "false", "PIPELINE_MODE": "standard", "DEPLOYMENT_ENVIRONMENT": "int"}
        self.assertEqual(release_rule(defaults), "on_success")
        settings = {**os.environ, **defaults, "CI_COMMIT_MESSAGE": "Configure repository [skip ci]", "CI_OPEN_MERGE_REQUESTS": "1"}
        rules = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())["workflow"]["rules"]
        selected = next(rule for rule in rules if subprocess.run(
            ["bash", "-c", "[[ " + rule["if"] + " ]]"], env=settings, capture_output=True).returncode == 0)
        self.assertEqual(selected.get("when", "on_success"), "on_success")
        self.assertEqual(release_rule(dict(defaults, APP_ONBOARDING="false")), "never")
        self.assertEqual(release_rule(dict(defaults, CI_PIPELINE_SOURCE="push")), "never")
        self.assertEqual(release_rule(dict(defaults, CI_PIPELINE_SOURCE="web", PIPELINE_MODE="full", DEPLOYMENT_ENVIRONMENT="uat")), "on_success")
        self.assertEqual(release_rule(dict(defaults, SONAR_SCAN_ONLY="true")), "never")
        self.assertEqual(release_rule(dict(defaults, RELEASE_VERSION="1.2.3")), "never")
        self.assertEqual(release_rule(dict(defaults, CI_COMMIT_BRANCH="feature", DEPLOYMENT_ENVIRONMENT="uat")), "absent")

    def test_dropdown_selects_a_target_and_promotion_skips_all_builds(self):
        pipeline = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())
        choice = pipeline["variables"]["DEPLOYMENT_ENVIRONMENT"]
        self.assertEqual(choice["value"], "int")
        self.assertEqual(choice["options"], ["int", "uat", "prod"])
        self.assertTrue(choice["description"])
        # Like Indezy and Thoughty, every build pipeline verifies the runtime images, and releases wait for it.
        self.assertEqual(pipeline["03-package"]["script"], ["infra/scripts/ci-container-build.sh verify"])
        self.assertEqual([(item["job"], item["artifacts"]) for item in pipeline["03-package"]["needs"]],
                         [("01-build", True)])
        for name in ("01-e2e", "01-release"):
            self.assertIn({"job": "03-package", "artifacts": False}, pipeline[name]["needs"])
        self.assertEqual(pipeline["01-release"]["resource_group"], "swirl-demo-app-release")
        self.assertEqual(pipeline["set-major-version"]["resource_group"], "swirl-demo-app-release")
        self.assertEqual(pipeline["02-deploy"]["resource_group"], "swirl-demo-app-$DEPLOYMENT_ENVIRONMENT")
        needs = {item["job"]: item for item in pipeline["02-deploy"]["needs"]}
        self.assertTrue(needs["01-release"]["optional"])
        self.assertTrue(needs["01-snapshot"]["optional"])
        self.assertFalse(needs["00-delivery-policy"].get("optional", False))
        self.assertEqual(pipeline["default"]["tags"], ["swirl-cloud-application-int"])
        self.assertEqual(pipeline["01-release"]["tags"], ["swirl-cloud-application-release"])
        self.assertEqual(pipeline["02-deploy"]["tags"], ["swirl-cloud-application-$DEPLOYMENT_ENVIRONMENT"])
        for name in ("01-build", "02-test", "03-package", "01-e2e", "04-coverage", "02-quality", "03-security",
                     "01-release", "set-major-version"):
            self.assertEqual(pipeline[name]["rules"][0],
                             {"if": '$RELEASE_VERSION != "" || $DEPLOYMENT_ENVIRONMENT == "prod"', "when": "never"})

    def test_prod_promotion_runs_from_the_ui_or_the_api_and_builds_nothing(self):
        pipeline = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())
        jobs = [name for name, job in pipeline.items() if isinstance(job, dict) and "rules" in job and name != "workflow"]
        promotion = {"CI_COMMIT_BRANCH": "trunk", "CI_DEFAULT_BRANCH": "trunk", "PIPELINE_MODE": "standard",
                     "DEPLOYMENT_ENVIRONMENT": "prod", "APP_ONBOARDING": "false", "SONAR_SCAN_ONLY": "false"}
        for source in ("web", "api"):
            started = {**promotion, "CI_PIPELINE_SOURCE": source}
            self.assertEqual(job_rule("workflow", started), "on_success", source)
            # Only the delivery policy (no rules) and the deployment run; neither needs build artifacts.
            selected = {name for name in jobs if job_rule(name, started) not in ("never", "absent")}
            self.assertEqual(selected, {"02-deploy"}, source)
            self.assertEqual(job_rule("02-deploy", {**started, "PIPELINE_MODE": "full"}), "on_success", source)
        self.assertNotIn("rules", pipeline["00-delivery-policy"])
        self.assertEqual([item["job"] for item in pipeline["02-deploy"]["needs"] if not item.get("optional")],
                         ["00-delivery-policy"])
        for source in ("push", "schedule", "trigger", "pipeline", "merge_request_event"):
            self.assertEqual(job_rule("02-deploy", {**promotion, "CI_PIPELINE_SOURCE": source}), "absent", source)
        self.assertEqual(job_rule("02-deploy", {**promotion, "CI_PIPELINE_SOURCE": "api", "APP_ONBOARDING": "true"}), "absent")
        self.assertEqual(job_rule("02-deploy", {**promotion, "CI_PIPELINE_SOURCE": "api", "CI_COMMIT_BRANCH": "feature"}), "absent")

    def test_jobs_start_as_soon_as_their_inputs_exist(self):
        pipeline = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())
        needs = {name: [item["job"] for item in job["needs"]] for name, job in pipeline.items()
                 if isinstance(job, dict) and isinstance(job.get("needs"), list)}
        for name in ("01-build", "02-test"):
            self.assertEqual(pipeline[name]["parallel"]["matrix"], [{"SUITE": ["server", "web"]}])
            self.assertIn('"$SUITE"', pipeline[name]["script"][0])
        # The tests and the source scan start after the delivery policy, not after the build.
        self.assertEqual(needs["02-test"], ["00-delivery-policy"])
        self.assertEqual(needs["03-security"], ["00-delivery-policy"])
        self.assertEqual(pipeline["02-test"]["variables"], {"KUBERNETES_CPU_LIMIT": "8", "KUBERNETES_MEMORY_LIMIT": "12Gi"})
        # Integration delivery waits only for the build: the snapshot builds and pushes the images itself.
        self.assertEqual(needs["01-snapshot"], ["01-build"])
        self.assertEqual(needs["03-package"], ["01-build"])
        # The combined coverage policy needs both test suites' reports.
        self.assertEqual(needs["04-coverage"], ["02-test"])
        self.assertEqual(pipeline["04-coverage"]["script"], ["infra/scripts/ci-verify.sh coverage"])
        self.assertTrue(pipeline["04-coverage"]["allow_failure"])
        self.assertNotIn("coverage", pipeline["02-test"])
        self.assertEqual(pipeline["04-coverage"]["rules"], pipeline["02-test"]["rules"])

    def test_nightly_e2e_schedule_runs_the_suite_and_nothing_after_it(self):
        schedule = {"CI_PIPELINE_SOURCE": "schedule", "CI_COMMIT_BRANCH": "trunk", "CI_DEFAULT_BRANCH": "trunk",
                    "PIPELINE_MODE": "standard", "DEPLOYMENT_ENVIRONMENT": "int", "APP_ONBOARDING": "false",
                    "SONAR_SCAN_ONLY": "false", "PLAYWRIGHT_SCHEDULE": "true"}
        for job in ("01-build", "02-test", "03-package", "04-coverage", "01-e2e"):
            self.assertEqual(job_rule(job, schedule), "on_success", job)
        for job in ("02-quality", "03-security", "01-release", "01-snapshot", "02-deploy", "01-publish",
                    "02-deploy-uat", "set-major-version"):
            self.assertEqual(job_rule(job, schedule), "never", job)
        self.assertEqual(job_rule("set-major-version", {**schedule, "NEW_MAJOR_VERSION": "2"}), "never")
        self.assertEqual(job_rule("01-e2e", {**schedule, "PLAYWRIGHT_SCHEDULE": "false"}), "manual")
        self.assertEqual(job_rule("01-e2e", {**schedule, "CI_PIPELINE_SOURCE": "push"}), "manual")
        pipeline = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())
        rule = next(rule for rule in pipeline["01-e2e"]["rules"] if "PLAYWRIGHT_SCHEDULE" in rule["if"])
        self.assertFalse(rule["allow_failure"])

    def test_any_branch_web_pipeline_including_open_merge_request_can_deploy_only_to_int(self):
        variables = {"CI_PIPELINE_SOURCE": "web", "CI_COMMIT_BRANCH": "feature/é;$(example)",
                     "CI_DEFAULT_BRANCH": "trunk", "CI_OPEN_MERGE_REQUESTS": "1", "PIPELINE_MODE": "full",
                     "DEPLOYMENT_ENVIRONMENT": "int", "APP_ONBOARDING": "false", "SONAR_SCAN_ONLY": "false"}
        self.assertEqual(job_rule("workflow", variables), "on_success")
        self.assertEqual(job_rule("01-snapshot", variables), "on_success")
        self.assertEqual(job_rule("01-release", variables), "never")
        self.assertEqual(job_rule("02-deploy", variables), "on_success")
        self.assertEqual(job_rule("01-snapshot", {**variables, "CI_COMMIT_BRANCH": "trunk"}), "on_success")
        self.assertEqual(job_rule("workflow", {**variables, "CI_PIPELINE_SOURCE": "push"}), "never")
        for environment in ("uat", "prod"):
            selected = {**variables, "DEPLOYMENT_ENVIRONMENT": environment}
            self.assertEqual(job_rule("01-snapshot", selected), "never")
            self.assertEqual(job_rule("01-release", selected), "never" if environment == "prod" else "absent")
            self.assertEqual(job_rule("02-deploy", selected), "absent")
            selected["CI_COMMIT_BRANCH"] = "trunk"
            # uat publishes and deploys a release; prod only promotes the release running on uat.
            self.assertEqual(job_rule("01-release", selected), "never" if environment == "prod" else "on_success")
            self.assertEqual(job_rule("02-deploy", selected), "on_success")
            # API-started pipelines deploy like web ones; prod still promotes only the uat release.
            self.assertEqual(job_rule("02-deploy", {**selected, "CI_PIPELINE_SOURCE": "api"}), "on_success")
        self.assertEqual(job_rule("01-snapshot", {**variables, "RELEASE_VERSION": "1.2.3"}), "never")
        self.assertEqual(job_rule("set-major-version", {**variables, "CI_COMMIT_BRANCH": "trunk"}), "never")
        major = {**variables, "CI_COMMIT_BRANCH": "trunk", "NEW_MAJOR_VERSION": "2", "PIPELINE_MODE": "standard"}
        self.assertEqual(job_rule("set-major-version", major), "manual")
        for job in ("01-snapshot", "01-release", "02-deploy"):
            self.assertEqual(job_rule(job, major), "never")

    def test_commits_deploy_int_and_default_branch_releases_deploy_uat(self):
        push = {"CI_PIPELINE_SOURCE": "push", "CI_COMMIT_BRANCH": "feature/x", "CI_DEFAULT_BRANCH": "trunk",
                "PIPELINE_MODE": "standard", "DEPLOYMENT_ENVIRONMENT": "int", "APP_ONBOARDING": "false",
                "SONAR_SCAN_ONLY": "false"}
        for branch in ("feature/x", "trunk"):
            commit = {**push, "CI_COMMIT_BRANCH": branch}
            self.assertEqual(job_rule("01-snapshot", commit), "on_success")
            self.assertEqual(job_rule("02-deploy", commit), "on_success")
            self.assertEqual(job_rule("01-release", commit), "never")
        self.assertEqual(job_rule("01-publish", push), "absent")
        self.assertEqual(job_rule("02-deploy-uat", push), "absent")
        trunk = {**push, "CI_COMMIT_BRANCH": "trunk"}
        self.assertEqual(job_rule("01-publish", trunk), "manual")
        self.assertEqual(job_rule("02-deploy-uat", trunk), "on_success")
        for source in ("web", "api"):
            self.assertEqual(job_rule("01-publish", {**trunk, "CI_PIPELINE_SOURCE": source}), "never")
            self.assertEqual(job_rule("02-deploy-uat", {**trunk, "CI_PIPELINE_SOURCE": source}), "never")
        pipeline = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text())
        self.assertEqual(pipeline["02-deploy-uat"]["variables"]["DEPLOYMENT_ENVIRONMENT"], "uat")
        self.assertEqual(pipeline["02-deploy-uat"]["tags"], ["swirl-cloud-application-uat"])
        self.assertEqual([item["job"] for item in pipeline["02-deploy-uat"]["needs"]], ["00-delivery-policy", "01-publish"])

    def test_revision_guard_rejects_stale_source_before_publication_and_accepts_release_descendant(self):
        with tempfile.TemporaryDirectory(prefix=APP + "-onboarding-git-") as directory:
            root = Path(directory)
            remote, work = root / "remote.git", root / "work"
            command(["git", "init", "--quiet", "--bare", "--initial-branch=main", str(remote)], root)
            command(["git", "clone", "--quiet", str(remote), str(work)], root)
            command(["git", "config", "user.name", "Onboarding test"], work)
            command(["git", "config", "user.email", "test@example.invalid"], work)
            command(["git", "config", "commit.gpgsign", "false"], work)
            (work / "infra/scripts").mkdir(parents=True)
            for name in ("ci-release.sh", "check-onboarding-revision.sh"):
                shutil.copy2(ROOT / "infra/scripts" / name, work / "infra/scripts" / name)
            command(["git", "add", "."], work)
            command(["git", "commit", "--quiet", "-m", "configured app"], work)
            initial = command(["git", "rev-parse", "HEAD"], work).stdout.strip()
            command(["git", "push", "--quiet", "origin", "main"], work)
            env = dict(os.environ, APP_ONBOARDING="true", CI_PIPELINE_SOURCE="api",
                       CI_COMMIT_BRANCH="main", CI_DEFAULT_BRANCH="main", CI_COMMIT_SHA=initial,
                       ONBOARDING_EXPECTED_SHA=initial, SONAR_SCAN_ONLY="false", APP_VERSION="1.0.0")
            guard = ["bash", "infra/scripts/check-onboarding-revision.sh"]
            command(guard + ["build"], work, env=env)
            command(guard + ["publish"], work, env=env)
            missing = dict(env, ONBOARDING_EXPECTED_SHA="")
            self.assertNotEqual(command(guard + ["publish"], work, env=missing, check=False).returncode, 0)
            command(["git", "commit", "--quiet", "--allow-empty", "-m", "release images"], work)
            command(["git", "commit", "--quiet", "--allow-empty", "-m", "next version"], work)
            released = command(["git", "rev-parse", "HEAD"], work).stdout.strip()
            command(["git", "push", "--quiet", "origin", "main"], work)
            command(["git", "checkout", "--quiet", "--detach", initial], work)
            stale = command(["bash", "infra/scripts/ci-release.sh", "publish"], work, env=env, check=False)
            self.assertNotEqual(stale.returncode, 0)
            self.assertIn("moved before publication", stale.stderr)
            self.assertNotIn("KANIKO_EXECUTOR", stale.stderr)
            command(guard + ["deploy"], work, env=dict(env, DEPLOY_REVISION=released))
            command(["git", "checkout", "--quiet", "main"], work)
            command(["git", "commit", "--quiet", "--allow-empty", "-m", "pin selected application"], work)
            pointer = command(["git", "rev-parse", "HEAD"], work).stdout.strip()
            command(["git", "push", "--quiet", "origin", "main"], work)
            command(guard + ["deploy"], work, env=dict(env, DEPLOY_REVISION=released, DEPLOY_COMMIT=pointer))
            self.assertNotEqual(command(guard + ["deploy"], work, env=dict(env, DEPLOY_REVISION=initial), check=False).returncode, 0)
            command(guard + ["publish"], work, env=dict(env, APP_ONBOARDING="false"))


if __name__ == "__main__":
    unittest.main()
