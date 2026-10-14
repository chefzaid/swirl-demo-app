#!/usr/bin/env python3
"""Verify Git promotion and int snapshots through the real delivery script with isolated Git/API fixtures."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import unittest


def fixtures():
    spec = importlib.util.spec_from_file_location("deployment_fixtures", Path(__file__).with_name("test-deployment.py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


DEPLOYMENT = fixtures()
ROOT, RENDER, PUBLICATION = DEPLOYMENT.ROOT, DEPLOYMENT.RENDER, DEPLOYMENT.PUBLICATION
INVENTORY, RELEASE = DEPLOYMENT.INVENTORY, DEPLOYMENT.RELEASE
copy_base, shared_inventory = DEPLOYMENT.copy_base, DEPLOYMENT.shared_inventory


KUBECTL = r'''#!/usr/bin/env python3
import json,os,pathlib,sys,yaml
path = pathlib.Path(os.environ['DEPLOYMENT_FIXTURE_STATE'])
state = json.loads(path.read_text())
args = [arg for arg in sys.argv[1:] if not arg.startswith('--request-timeout=')]
if args[:2] == ['get','configmap']:
    print(json.dumps({'data': {'environments.json': json.dumps(state['inventory'])}}))
elif args[:2] == ['get','application']:
    app = state.get('applications', {}).get(args[2])
    if app: print(json.dumps(app))
    elif '--ignore-not-found' not in args: sys.exit(1)
elif args[0] == 'apply':
    app = yaml.safe_load(pathlib.Path(args[args.index('-f') + 1]).read_text())
    metadata = json.loads(pathlib.Path('package-output/deployment.json').read_text())
    app['status'] = {'sync': {'revision': app['spec']['source']['targetRevision'], 'status': 'Synced'},
      'health': {'status': 'Healthy'}, 'summary': {'images': metadata['images']},
      'resources': [{'kind':'Deployment','name':name,'status':'Synced'} for name in ('swirlapp-user','swirlapp-order','swirlapp-web')]}
    if state.get('wrong_destination'): app['spec']['destination']['name'] = 'wrong-cluster'
    state.setdefault('applications', {})[app['metadata']['name']] = app
    state.setdefault('applied', []).append(app['metadata']['name'])
    path.write_text(json.dumps(state))
elif args[0] != 'annotate':
    raise RuntimeError('Unexpected kubectl invocation: ' + repr(args))
'''


class PromotionTests(unittest.TestCase):
    def setUp(self):
        self.fixture = PUBLICATION.PublicationTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        fixture = self.fixture
        copy_base(fixture.work)
        shutil.copy2(ROOT / "infra/scripts/render-deployment.py", fixture.work / "infra/scripts/render-deployment.py")
        fixture.write("infra/deployment-environments.json", json.dumps(INVENTORY))
        fixture.git("add", ".")
        fixture.git("commit", "-qm", "Configure central deployment inventory")
        fixture.source = fixture.git("rev-parse", "HEAD").stdout.strip()
        fixture.env.update(CI_COMMIT_SHA=fixture.source, ONBOARDING_EXPECTED_SHA=fixture.source)
        fixture.git("push", "-q", "origin", "trunk")
        result = fixture.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.state_path = fixture.root / "deployment-state.json"
        self.state_path.write_text(json.dumps({"inventory": INVENTORY}))
        (fixture.root / "bin/kubectl").write_text(KUBECTL)
        (fixture.root / "bin/kubectl").chmod(0o700)
        curl = fixture.root / "bin/curl"
        text = curl.read_text().replace("state_path =", '''if any(arg.startswith('https://demo.') for arg in sys.argv[1:]):
    metadata = json.loads(pathlib.Path('package-output/deployment.json').read_text())
    url = sys.argv[-1]
    assert url.startswith(metadata['url'] + '/')
    if url.endswith('/runtime-config.json'): print(json.dumps(metadata['runtimeConfig']))
    elif '/health/' in url: print('{"status":"UP"}')
    else: print('<html><app-root></app-root></html>')
    sys.exit(0)
state_path =''', 1)
        curl.write_text(text)
        self.env = {**fixture.env, "DEPLOYMENT_ENVIRONMENT": "int", "DEPLOYMENT_FIXTURE_STATE": str(self.state_path)}

    def state(self, **changes):
        state = json.loads(self.state_path.read_text())
        state.update(changes)
        self.state_path.write_text(json.dumps(state))
        return state

    def deploy(self, **changes):
        return subprocess.run(["bash", "infra/scripts/ci-release.sh", "deploy"], cwd=self.fixture.work,
                              env={**self.env, **changes}, text=True, capture_output=True, timeout=30)

    def test_one_publication_promotes_across_targets_without_rebuilding_or_repointing_other_apps(self):
        fixture = self.fixture
        previous = {}
        for index, environment in enumerate(RENDER.ENVIRONMENTS):
            changes = {"DEPLOYMENT_ENVIRONMENT": environment}
            if index:
                changes.update(RELEASE_VERSION="1.0.1", APP_ONBOARDING="false", CI_PIPELINE_SOURCE="web",
                               CI_PIPELINE_ID=str(74 + index), CI_COMMIT_SHA=fixture.git("rev-parse", "origin/trunk").stdout.strip())
            result = self.deploy(**changes)
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
            state = self.state()
            for name, app in previous.items():
                self.assertEqual(state["applications"][name], app)
            previous = copy.deepcopy(state["applications"])
            app = state["applications"]["swirl-demo-app-" + environment]
            runtime = app["spec"]["source"]["targetRevision"]
            tip = fixture.git("rev-parse", "origin/trunk").stdout.strip()
            self.assertNotEqual(runtime, tip)
            self.assertEqual(fixture.git("rev-parse", tip + "^").stdout.strip(), runtime)
            self.assertEqual(app["spec"]["destination"]["name"], "apps-" + environment)
            self.assertEqual(fixture.builds.read_text(), "build\n")
            self.assertEqual(fixture.git("show", "origin/trunk:VERSION").stdout, "1.1.0\n")
        image_sets = [app["status"]["summary"]["images"] for app in previous.values()]
        self.assertTrue(all(images == image_sets[0] for images in image_sets))

    def test_prod_only_promotes_the_verified_uat_release(self):
        fixture = self.fixture
        def web(environment, index, **changes):
            return self.deploy(DEPLOYMENT_ENVIRONMENT=environment, APP_ONBOARDING="false", CI_PIPELINE_SOURCE="web",
                               CI_PIPELINE_ID=str(120 + index), **changes,
                               CI_COMMIT_SHA=fixture.git("rev-parse", "origin/trunk").stdout.strip())
        refused = web("prod", 0, RELEASE_VERSION="1.0.1")
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("deploy a release to uat first", refused.stderr)
        self.assertEqual(web("uat", 1, RELEASE_VERSION="1.0.1").returncode, 0)
        mismatch = web("prod", 2, RELEASE_VERSION="1.0.2")
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("running on uat (1.0.1)", mismatch.stderr)
        state = self.state()
        state["applications"]["swirl-demo-app-uat"]["status"]["health"]["status"] = "Degraded"
        self.state(applications=state["applications"])
        unhealthy = web("prod", 3)
        self.assertNotEqual(unhealthy.returncode, 0)
        self.assertIn("uat is not Synced/Healthy", unhealthy.stderr)
        self.assertNotIn("swirl-demo-app-prod", self.state().get("applied", []))
        state["applications"]["swirl-demo-app-uat"]["status"]["health"]["status"] = "Healthy"
        self.state(applications=state["applications"])
        promoted = web("prod", 4)
        self.assertEqual(promoted.returncode, 0, promoted.stderr + promoted.stdout)
        self.assertIn("swirl-demo-app-prod", self.state()["applied"])
        tip = fixture.git("rev-parse", "origin/trunk").stdout.strip()
        self.assertEqual(fixture.git("show", "-s", "--format=%s", tip).stdout.strip(), "deploy: pin prod to 1.0.1 [skip ci]")

    def test_retry_keeps_the_same_git_pointer_and_does_not_rebuild(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        tip = self.fixture.git("rev-parse", "origin/trunk").stdout
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(tip, self.fixture.git("rev-parse", "origin/trunk").stdout)
        self.assertEqual(self.fixture.builds.read_text(), "build\n")

    def test_shared_cluster_promotion_updates_only_the_selected_namespace(self):
        fixture = self.fixture
        inventory = shared_inventory()
        fixture.write("infra/deployment-environments.json", json.dumps(inventory))
        fixture.git("add", "infra/deployment-environments.json")
        fixture.git("commit", "-qm", "Select shared cluster environments")
        fixture.git("push", "-q", "origin", "trunk")
        self.state(inventory=inventory)
        previous = {}
        for index, environment in enumerate(RENDER.ENVIRONMENTS):
            result = self.deploy(DEPLOYMENT_ENVIRONMENT=environment, RELEASE_VERSION="1.0.1", APP_ONBOARDING="false",
                                CI_PIPELINE_SOURCE="web", CI_PIPELINE_ID=str(90 + index),
                                CI_COMMIT_SHA=fixture.git("rev-parse", "origin/trunk").stdout.strip())
            self.assertEqual(result.returncode, 0, result.stderr)
            applications = self.state()["applications"]
            self.assertEqual(applications["swirl-demo-app-" + environment]["spec"]["destination"],
                             {"name": "in-cluster", "namespace": "apps" if environment == "prod" else "apps-" + environment})
            for name, application in previous.items():
                self.assertEqual(applications[name], application)
            previous = copy.deepcopy(applications)
        self.assertEqual(fixture.builds.read_text(), "build\n")

    def test_reassigned_cluster_and_unregistered_environment_never_apply(self):
        changed = copy.deepcopy(INVENTORY)
        changed["environments"]["int"]["clusterName"] = "foreign-cluster"
        self.state(inventory=changed)
        tip = self.fixture.git("rev-parse", "origin/trunk").stdout
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertEqual(tip, self.fixture.git("rev-parse", "origin/trunk").stdout)
        self.assertFalse(self.state().get("applied"))
        self.state(inventory={**INVENTORY, "environments": {}})
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertFalse(self.state().get("applied"))

    def test_advanced_branch_and_invalid_environment_never_apply(self):
        self.fixture.git("commit", "--allow-empty", "-qm", "Another release changed source")
        self.fixture.git("push", "-q", "origin", "trunk")
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertNotEqual(self.deploy(DEPLOYMENT_ENVIRONMENT="../prod").returncode, 0)
        self.assertFalse(self.state().get("applied"))

    def test_unfinalized_release_is_rejected_by_delivery_and_direct_live_renderer(self):
        fixture = self.fixture
        fixture.state(release=None)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        manifest = fixture.git("show", "v1.0.1:infra/releases/1.0.1.json").stdout
        fixture.write("package-output/deployment-release.json", manifest)
        result = subprocess.run(["python3", "infra/scripts/render-deployment.py", "--inventory",
            "infra/deployment-environments.json", "--environment", "prod", "--release",
            "package-output/deployment-release.json", "--revision", fixture.source, "--verify-live"],
            cwd=fixture.work, env=self.env, text=True, capture_output=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not finalized", result.stderr)
        self.assertFalse(self.state().get("applied"))

    def test_wrong_live_destination_cannot_report_success(self):
        self.state(wrong_destination=True)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("target or pinned revision changed", result.stderr)


class SnapshotTests(PromotionTests):
    # Reuse setup helpers, but run only snapshot cases in this subclass.
    test_one_publication_promotes_across_targets_without_rebuilding_or_repointing_other_apps = None
    test_retry_keeps_the_same_git_pointer_and_does_not_rebuild = None
    test_reassigned_cluster_and_unregistered_environment_never_apply = None
    test_advanced_branch_and_invalid_environment_never_apply = None
    test_wrong_live_destination_cannot_report_success = None
    test_unfinalized_release_is_rejected_by_delivery_and_direct_live_renderer = None
    test_shared_cluster_promotion_updates_only_the_selected_namespace = None
    test_prod_only_promotes_the_verified_uat_release = None

    def prepare_branch(self, branch="feature/é;$(example)", pipeline="84"):
        fixture = self.fixture
        fixture.git("checkout", "-q", "trunk")
        fixture.write("infra/environments/int/settings.json", json.dumps({"appSubdomain": "demo"}))
        fixture.git("add", "infra/environments/int/settings.json")
        fixture.git("commit", "-qm", "Current integration settings")
        fixture.git("push", "-q", "origin", "trunk")
        self.default_tip = fixture.git("rev-parse", "origin/trunk").stdout.strip()
        fixture.git("checkout", "-q", "-b", branch)
        fixture.write("VERSION", "2.0.0-SNAPSHOT\n")
        fixture.write("feature-code.txt", "Feature source, unchanged by delivery\n")
        fixture.write("infra/environments/int/settings.json", json.dumps({"appSubdomain": "stale-branch-host"}))
        fixture.git("add", "VERSION", "feature-code.txt", "infra/environments/int/settings.json")
        fixture.git("commit", "-qm", "Unreleased feature")
        source = fixture.git("rev-parse", "HEAD").stdout.strip()
        fixture.git("push", "-q", "origin", "HEAD:refs/heads/" + branch)
        self.env.update(APP_ONBOARDING="false", CI_PIPELINE_SOURCE="web", CI_PIPELINE_ID=pipeline,
                        CI_COMMIT_BRANCH=branch, CI_COMMIT_SHA=source)
        (fixture.work / "release.env").unlink(missing_ok=True)
        self.snapshot_branch = "gitops/int/" + pipeline
        return source

    def snapshot(self, **changes):
        return subprocess.run(["bash", "infra/scripts/ci-release.sh", "snapshot"], cwd=self.fixture.work,
                              env={**self.env, **changes}, text=True, capture_output=True, timeout=30)

    def test_snapshot_keeps_branch_main_versions_and_other_targets_unchanged_and_retries_without_rebuilding(self):
        source = self.prepare_branch()
        fixture = self.fixture
        tags = fixture.git("ls-remote", "--tags", "origin").stdout
        posts = fixture.state()["posts"]
        result = self.snapshot()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        for _ in range(2):
            result = self.deploy()
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        app = self.state()["applications"]["swirl-demo-app-int"]
        runtime = app["spec"]["source"]["targetRevision"]
        pointer = fixture.git("rev-parse", "origin/" + self.snapshot_branch).stdout.strip()
        self.assertEqual(fixture.git("rev-parse", pointer + "^").stdout.strip(), runtime)
        self.assertEqual(fixture.git("show", runtime + ":VERSION").stdout, "2.0.0-SNAPSHOT\n")
        self.assertEqual(fixture.git("show", runtime + ":feature-code.txt").stdout, "Feature source, unchanged by delivery\n")
        self.assertEqual(fixture.git("rev-parse", "origin/" + self.env["CI_COMMIT_BRANCH"]).stdout.strip(), source)
        self.assertEqual(fixture.git("rev-parse", "origin/trunk").stdout.strip(), self.default_tip)
        self.assertEqual(fixture.git("ls-remote", "--tags", "origin").stdout, tags)
        self.assertEqual(set(self.state()["applications"]), {"swirl-demo-app-int"})
        self.assertEqual(fixture.state()["posts"], posts)
        manifest = json.loads(fixture.git("show", runtime + ":infra/snapshots/84.json").stdout)
        self.assertEqual(manifest["sourceBranch"], self.env["CI_COMMIT_BRANCH"])
        self.assertEqual(manifest["releaseVersion"], "snapshot-" + source + "-84")
        self.assertEqual(json.loads(fixture.git("show", runtime + ":infra/environments/int/settings.json").stdout)["appSubdomain"], "demo")
        fixture.git("checkout", "-q", "--detach", source)
        (fixture.work / "release.env").unlink()
        result = self.snapshot()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(fixture.builds.read_text(), "build\nbuild\n")
        self.assertEqual(fixture.git("rev-parse", "origin/" + self.snapshot_branch).stdout.strip(), pointer)

    def test_snapshots_and_branch_promotions_cannot_reach_uat_or_prod(self):
        self.prepare_branch()
        for environment in ("uat", "prod"):
            self.assertNotEqual(self.snapshot(DEPLOYMENT_ENVIRONMENT=environment).returncode, 0)
            for release in ("", "1.0.1", "1.0.1-SNAPSHOT", "snapshot-latest"):
                with self.subTest(environment=environment, release=release):
                    result = self.deploy(DEPLOYMENT_ENVIRONMENT=environment, RELEASE_VERSION=release)
                    self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.state().get("applied"))
        self.assertEqual(self.fixture.builds.read_text(), "build\n")

    def test_source_advance_before_publication_or_deploy_is_rejected(self):
        self.prepare_branch(branch="feature/slash/nested")
        self.assertEqual(self.snapshot().returncode, 0)
        fixture = self.fixture
        fixture.git("checkout", "-q", self.env["CI_COMMIT_BRANCH"])
        fixture.git("commit", "--allow-empty", "-qm", "Feature advanced")
        fixture.git("push", "-q", "origin", "HEAD:refs/heads/" + self.env["CI_COMMIT_BRANCH"])
        for result in (self.snapshot(), self.deploy()):
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("source branch advanced", result.stderr)
        self.assertFalse(self.state().get("applied"))

    def test_older_snapshot_cannot_replace_a_newer_pipeline(self):
        self.prepare_branch()
        self.assertEqual(self.snapshot().returncode, 0)
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.state()
        state["applications"]["swirl-demo-app-int"]["metadata"]["annotations"]["swirl-demo-app.delivery/pipeline"] = "85"
        self.state(**state)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("newer pipeline", result.stderr)
        self.assertEqual(len(self.state()["applied"]), 1)

    def test_missing_or_tampered_snapshot_receipts_never_deploy(self):
        self.prepare_branch()
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertEqual(self.snapshot().returncode, 0)
        fixture = self.fixture
        path = fixture.work / "infra/snapshots/84.json"
        manifest = json.loads(path.read_text())
        manifest["images"]["swirlapp-user"]["digest"] = "sha256:" + "f" * 64
        path.write_text(json.dumps(manifest))
        fixture.git("add", "infra/snapshots/84.json")
        fixture.git("commit", "-qm", "Tamper with snapshot receipt")
        fixture.git("push", "-q", "origin", "HEAD:refs/heads/" + self.snapshot_branch)
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertFalse(self.state().get("applied"))


if __name__ == "__main__":
    unittest.main()
