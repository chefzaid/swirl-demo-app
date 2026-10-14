#!/usr/bin/env python3
"""Verify real environment rendering and Git promotion with isolated Git/API fixtures."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml


ROOT = Path(__file__).resolve().parents[2]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "infra/scripts" / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


RENDER = module("deployment_renderer", "render-deployment.py")
PUBLICATION = module("publication_fixture", "test-release.py")
INVENTORY = {
    "version": 1,
    "platform": {"domain": "example.test", "internalDomain": "services.test", "services": {
        "postgres": {"host": "100.100.0.10", "port": 30432},
        "redis": {"host": "100.100.0.10", "port": 30379},
        "kafka": {"bootstrapServers": "100.100.0.10:30940", "securityProtocol": "SASL_PLAINTEXT"},
        "vault": {"url": "http://100.100.0.10:30820"},
        "registry": {"host": "registry.example.test", "mirrorEndpoint": "http://100.100.0.10:30500"},
        "keycloak": {"url": "https://keycloak.example.test/auth", "realm": "example"}}},
    "environments": {name: {"clusterName": "apps-" + name, "server": f"https://100.100.{index}.10:6443",
        "namespace": "apps", "domain": (name + "." if name != "prod" else "") + "example.test",
        "ingressAddress": f"203.0.113.{index}", "podCIDR": "10.42.0.0/16", "nodeCIDRs": [f"100.100.{index}.10/32"]}
        for index, name in enumerate(("int", "uat", "prod"), 1)}}
RELEASE = {"version": 1, "releaseVersion": "1.0.1", "sourceRevision": "a" * 40, "pipelineId": "73",
           "images": {name: {"repository": "registry.example.test/teams/testing/swirl-demo-app/" + name,
                             "digest": "sha256:" + str(index) * 64}
                      for index, name in enumerate(RENDER.IMAGES, 1)}}


def shared_inventory():
    inventory = copy.deepcopy(INVENTORY)
    services = copy.deepcopy(inventory["platform"]["services"])
    services["postgres"] = {"host": "postgres.infra.svc.cluster.local", "port": 5432}
    services["redis"] = {"host": "redis-master.infra.svc.cluster.local", "port": 6379}
    services["kafka"]["bootstrapServers"] = "kafka.infra.svc.cluster.local:9094"
    for environment, entry in inventory["environments"].items():
        entry.update(mode="local", clusterName="in-cluster", server="https://kubernetes.default.svc",
                     namespace="apps" if environment == "prod" else "apps-" + environment, ingressAddress="203.0.113.10",
                     secretStoreName="vault-backend-" + environment, services=copy.deepcopy(services))
    return inventory


def copy_base(destination, source=ROOT):
    for name in ("k8s", "argocd", "overlays", "observability"):
        shutil.copytree(source / "infra" / name, destination / "infra" / name, dirs_exist_ok=True)
    # CI runs this check after onboarding has rewritten the template's project
    # and image names. Derive fixture substitutions from the current manifests.
    kustomization = yaml.safe_load((destination / "infra/k8s/kustomization.yaml").read_text())
    replacements = {item["name"]: RELEASE["images"][item["name"].rsplit("/", 1)[-1]]["repository"]
                    for item in kustomization["images"]}
    for path in (destination / "infra").rglob("*"):
        if path.is_file() and path.suffix in (".yaml", ".json", ".properties"):
            content = path.read_text()
            for original, fixture in replacements.items():
                content = content.replace(original, fixture)
            path.write_text(content)
    path = destination / "infra/argocd/application.yaml"
    application = yaml.safe_load(path.read_text())
    application["spec"]["source"]["repoURL"] = "http://gitlab.services.test/teams/testing/swirl-demo-app.git"
    path.write_text(yaml.safe_dump(application, sort_keys=False))


class RenderingTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"CI_PROJECT_PATH": "teams/testing/swirl-demo-app"})
        environment.start()
        self.addCleanup(environment.stop)
        temporary = tempfile.TemporaryDirectory(prefix="swirl-demo-app-environments-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_base(self.root)

    def test_fixture_accepts_an_already_parameterized_checkout(self):
        with tempfile.TemporaryDirectory(prefix="swirl-demo-app-configured-fixture-") as temporary:
            source = Path(temporary) / "configured"
            fixture = Path(temporary) / "fixture"
            copy_base(source)
            for path in (source / "infra").rglob("*"):
                if path.is_file() and path.suffix in (".yaml", ".json", ".properties"):
                    path.write_text(path.read_text().replace("registry.example.test/teams/testing/swirl-demo-app",
                        "registry.customer.test/nested/group/portal").replace(
                            "http://gitlab.services.test/teams/testing/swirl-demo-app.git",
                            "https://gitlab.customer.test/nested/group/portal.git"))
            copy_base(fixture, source)
            metadata = RENDER.render(fixture, INVENTORY, "int", RELEASE, "a" * 40)
            rendered = subprocess.run(["kubectl", "kustomize", str(fixture / "infra/environments/int")],
                                      text=True, capture_output=True, check=True)
            images = [item["spec"]["template"]["spec"]["containers"][0]["image"]
                      for item in yaml.safe_load_all(rendered.stdout) if item["kind"] == "Deployment"]
            self.assertEqual(set(images), set(metadata["images"]))

    def test_renderer_still_rejects_a_foreign_ci_project(self):
        with patch.dict(os.environ, {"CI_PROJECT_PATH": "another/team/project"}):
            with self.assertRaisesRegex(ValueError, "running GitLab project"):
                RENDER.render(self.root, INVENTORY, "int", RELEASE, "a" * 40)

    def test_three_targets_have_isolated_configuration_and_the_same_digests(self):
        previous = {}
        for index, environment in enumerate(RENDER.ENVIRONMENTS, 1):
            revision = str(index) * 40
            metadata = RENDER.render(self.root, INVENTORY, environment, RELEASE, revision)
            for name, contents in previous.items():
                self.assertEqual((self.root / name).read_bytes(), contents)
            application = yaml.safe_load((self.root / f"infra/argocd/{environment}.yaml").read_text())
            self.assertEqual(application["spec"]["destination"], {"name": "apps-" + environment, "namespace": "apps"})
            self.assertEqual(application["spec"]["source"]["targetRevision"], revision)
            result = subprocess.run(["kubectl", "kustomize", f"infra/environments/{environment}"], cwd=self.root,
                                    text=True, capture_output=True, check=True)
            resources = list(yaml.safe_load_all(result.stdout))
            self.assertTrue(all(item["metadata"]["annotations"]["argocd.argoproj.io/sync-wave"] == "10"
                                for item in resources if item["kind"] == "Ingress"))
            images = [item["spec"]["template"]["spec"]["containers"][0]["image"] for item in resources if item["kind"] == "Deployment"]
            self.assertEqual(set(images), set(metadata["images"]))
            backend = next(item for item in resources if item["kind"] == "ConfigMap" and "DB_NAME" in item.get("data", {}))
            self.assertEqual(backend["data"]["DB_NAME"], "swirl_demo_app_" + environment)
            self.assertEqual(backend["data"]["KAFKA_TOPIC_PREFIX"], "swirl-demo-app." + environment + ".")
            self.assertEqual(backend["data"]["REDIS_CACHE_PREFIX"], "swirl-demo-app:" + environment + ":")
            self.assertEqual(backend["data"]["JWT_AUDIENCE"], "swirl-demo-app-" + environment + "-web")
            secret = next(item for item in resources if item["kind"] == "ExternalSecret" and item["metadata"]["name"] == "swirl-demo-app-runtime-credentials")
            self.assertEqual({item["remoteRef"]["key"] for item in secret["spec"]["data"]},
                             {f"apps/swirl-demo-app/{environment}/{service}" for service in ("database", "redis", "kafka")})
            self.assertFalse(any(item["kind"] == "Job" and item["metadata"]["name"] == "swirl-demo-app-db-setup" for item in resources))
            entry = next(item for item in resources if item["kind"] == "Ingress" and item["metadata"]["name"] == "swirl-demo-app-ingress")
            self.assertEqual(entry["metadata"]["annotations"]["gethomepage.dev/name"], "Swirl Demo App")
            self.assertEqual(entry["metadata"]["annotations"]["gethomepage.dev/group"], "Applications - " + environment.upper())
            previous.update({str(path.relative_to(self.root)): path.read_bytes()
                             for path in (self.root / f"infra/environments/{environment}").rglob("*") if path.is_file()})
            previous[f"infra/argocd/{environment}.yaml"] = (self.root / f"infra/argocd/{environment}.yaml").read_bytes()

    def test_apex_custom_cidrs_and_ha_are_preserved_on_rerun(self):
        settings = self.root / "infra/environments/uat/settings.json"
        settings.parent.mkdir(parents=True)
        settings.write_text(json.dumps({"appSubdomain": "@", "trustedProxyCIDRs": "10.60.0.0/16", "highAvailability": True}))
        metadata = RENDER.render(self.root, INVENTORY, "uat", RELEASE, "a" * 40)
        self.assertEqual(metadata["host"], "uat.example.test")
        snapshot = {p: p.read_bytes() for p in settings.parent.iterdir()}
        RENDER.render(self.root, INVENTORY, "uat", RELEASE, "a" * 40)
        self.assertEqual(snapshot, {p: p.read_bytes() for p in settings.parent.iterdir()})
        rendered = subprocess.run(["kubectl", "kustomize", str(settings.parent)], text=True, capture_output=True, check=True)
        resources = list(yaml.safe_load_all(rendered.stdout))
        deployments = {item["metadata"]["name"] for item in resources if item["kind"] == "Deployment"}
        autoscalers = {item["spec"]["scaleTargetRef"]["name"]: item["spec"]["minReplicas"]
                       for item in resources if item["kind"] == "HorizontalPodAutoscaler"}
        self.assertTrue(all("replicas" not in item["spec"] for item in resources if item["kind"] == "Deployment"))
        self.assertEqual(dict.fromkeys(deployments, 2), autoscalers)

    def test_shared_cluster_resources_and_references_are_isolated_by_namespace(self):
        inventory = shared_inventory()
        identities, dashboard_ids, dashboard_files = set(), set(), set()
        for environment in RENDER.ENVIRONMENTS:
            namespace = "apps" if environment == "prod" else "apps-" + environment
            metadata = RENDER.render(self.root, inventory, environment, RELEASE, "a" * 40)
            self.assertEqual(metadata["destination"], {"name": "in-cluster", "namespace": namespace})
            rendered = subprocess.run(["kubectl", "kustomize", str(self.root / "infra/environments" / environment)],
                                      text=True, capture_output=True, check=True)
            resources = list(yaml.safe_load_all(rendered.stdout))
            policies = [item["metadata"]["name"] for item in resources if item["kind"] == "NetworkPolicy"]
            # Dedicated namespaces use platform policies; the shared prod namespace needs the app's own.
            self.assertEqual(policies, ["allow-swirl-demo-app-egress", "allow-swirl-demo-app-ingress"] if environment == "prod" else [])
            for item in resources:
                self.assertEqual(item["metadata"]["namespace"], namespace)
                identity = (item["apiVersion"], item["kind"], namespace, item["metadata"]["name"])
                self.assertNotIn(identity, identities)
                identities.add(identity)
                annotations = item["metadata"].get("annotations", {})
                for key in ("traefik.ingress.kubernetes.io/router.middlewares",
                            "traefik.ingress.kubernetes.io/service.serverstransport"):
                    if key in annotations:
                        self.assertTrue(annotations[key].startswith(namespace + "-swirl-demo-app-"))
                if item["kind"] == "ExternalSecret":
                    self.assertEqual(item["spec"]["secretStoreRef"]["name"], "vault-backend-" + environment)
                if item["kind"] == "Ingress":
                    self.assertTrue(item["spec"]["tls"][0]["secretName"])
                if item["kind"] == "ConfigMap" and "DB_HOST" in item.get("data", {}):
                    self.assertEqual(item["data"]["DB_HOST"], "postgres.infra.svc.cluster.local")
                    self.assertEqual(item["data"]["DB_NAME"], "swirl_demo_app_" + environment)
                if item["kind"] == "ConfigMap" and item["metadata"]["name"] == "swirl-demo-app-grafana-dashboard":
                    filename, content = next(iter(item["data"].items()))
                    dashboard = json.loads(content)
                    self.assertNotIn(filename, dashboard_files)
                    self.assertNotIn(dashboard["uid"], dashboard_ids)
                    dashboard_files.add(filename)
                    dashboard_ids.add(dashboard["uid"])
                    self.assertIn(rf'namespace=\"{namespace}\"', content)
                    for other in {"apps", "apps-int", "apps-uat"} - {namespace}:
                        self.assertNotIn(rf'namespace=\"{other}\"', content)

    def test_each_environment_ships_alert_rules_for_its_own_namespace(self):
        source = (ROOT / "infra/observability/slo-rules.yaml").read_text()
        for environment in RENDER.ENVIRONMENTS:
            namespace = "apps" if environment == "prod" else "apps-" + environment
            RENDER.render(self.root, shared_inventory(), environment, RELEASE, "a" * 40)
            rendered = subprocess.run(["kubectl", "kustomize", str(self.root / "infra/environments" / environment)],
                                      text=True, capture_output=True, check=True)
            (rules,) = [item for item in yaml.safe_load_all(rendered.stdout) if item["kind"] == "ConfigMap"
                        and item["metadata"].get("labels", {}).get("prometheus_rule") == "1"]
            self.assertEqual(("swirl-demo-app-prometheus-rules", namespace), (rules["metadata"]["name"], rules["metadata"]["namespace"]))
            # The promtool tests prove a copy ignores other namespaces; the copies differ only in that name.
            self.assertEqual({f"swirl-demo-app-{environment}-slo-rules.yaml": source.replace('namespace="apps"', f'namespace="{namespace}"')},
                             rules["data"])
            groups = yaml.safe_load(rules["data"][f"swirl-demo-app-{environment}-slo-rules.yaml"])["groups"]
            expressions = [rule["expr"] for group in groups for rule in group["rules"]]
            self.assertTrue(all(f'namespace="{namespace}"' in expression for expression in expressions))
            self.assertFalse(any(f'namespace="{other}"' in expression for expression in expressions
                                 for other in {"apps", "apps-int", "apps-uat"} - {namespace}))

    def test_remote_clusters_can_mix_with_local_environments(self):
        inventory = shared_inventory()
        for index, environment in enumerate(("uat", "prod"), 1):
            inventory["environments"][environment].update(mode="remote", clusterName="release-" + environment,
                                                          server=f"https://100.100.0.{index}:6443")
        for environment in RENDER.ENVIRONMENTS:
            RENDER.render(self.root, inventory, environment, RELEASE, "a" * 40)
        inventory["environments"]["uat"]["server"] = inventory["environments"]["prod"]["server"]
        with self.assertRaisesRegex(ValueError, "distinct registered clusters"):
            RENDER.render(self.root, inventory, "prod", RELEASE, "a" * 40)
        inventory = shared_inventory()
        inventory["environments"]["uat"]["namespace"] = "apps"
        with self.assertRaisesRegex(ValueError, "distinct namespaces"):
            RENDER.render(self.root, inventory, "prod", RELEASE, "a" * 40)

    def test_shared_cluster_cannot_target_platform_or_legacy_namespaces(self):
        for namespace in ("apps", "infra", "corp", "default", "gitlab-runners", "kube-system", "longhorn-system"):
            inventory = shared_inventory()
            inventory["environments"]["int"]["namespace"] = namespace
            with self.subTest(namespace=namespace), self.assertRaisesRegex(ValueError, "application namespace|dedicated namespaces"):
                RENDER.render(self.root, inventory, "int", RELEASE, "a" * 40)
        for environment in ("int", "uat"):
            inventory = shared_inventory()
            inventory["environments"]["prod"]["namespace"] = "apps-prod"
            inventory["environments"][environment]["namespace"] = "apps"
            with self.subTest(environment=environment), self.assertRaisesRegex(ValueError, "Only local prod"):
                RENDER.render(self.root, inventory, environment, RELEASE, "a" * 40)

    def test_suffix_hostnames_preserve_app_labels_on_rerun_and_support_apex_selection(self):
        inventory = shared_inventory()
        inventory["platform"]["hostnameStyle"] = "suffix"
        for environment, selected in inventory["environments"].items():
            selected["domain"] = "example.test"
            selected["hostnameSuffix"] = "-" + environment if environment != "prod" else ""
            path = self.root / "infra/environments" / environment / "settings.json"
            for _ in range(2):
                result = RENDER.render(self.root, inventory, environment, RELEASE, "a" * 40)
                self.assertEqual(result["host"], "demo" + selected["hostnameSuffix"] + ".example.test")
                self.assertEqual(json.loads(path.read_text())["appSubdomain"], "demo")
            rendered = subprocess.run(["kubectl", "kustomize", str(path.parent)], text=True, capture_output=True, check=True)
            resources = list(yaml.safe_load_all(rendered.stdout))
            ingresses = [item for item in resources if item["kind"] == "Ingress"]
            self.assertEqual(len(ingresses), 2)
            self.assertTrue(all(item["spec"]["tls"] == [{"hosts": [result["host"]]}] for item in ingresses))
            self.assertFalse(any(item["kind"] == "Secret" and item.get("type") == "kubernetes.io/tls" for item in resources))
            path.write_text(json.dumps({"appSubdomain": "@"}))
            for _ in range(2):
                result = RENDER.render(self.root, inventory, environment, RELEASE, "a" * 40)
                self.assertEqual(result["host"], (environment + "." if environment != "prod" else "") + "example.test")
                self.assertEqual(json.loads(path.read_text())["appSubdomain"], "@")
        inventory["environments"]["uat"].update(mode="remote", clusterName="remote-uat", server="https://100.100.0.20:6443")
        RENDER.render(self.root, inventory, "uat", RELEASE, "a" * 40)
        rendered = subprocess.run(["kubectl", "kustomize", str(self.root / "infra/environments/uat")],
                                  text=True, capture_output=True, check=True)
        self.assertTrue(all(item["spec"]["tls"][0]["secretName"] for item in yaml.safe_load_all(rendered.stdout)
                            if item["kind"] == "Ingress"))
        inventory["environments"]["int"]["hostnameSuffix"] = "-prod"
        with self.assertRaisesRegex(ValueError, "hostname suffix"):
            RENDER.render(self.root, inventory, "int", RELEASE, "a" * 40)

    def test_invalid_target_and_foreign_image_are_rejected_before_output(self):
        for change in ("environment", "local", "namespace", "digest", "registry"):
            inventory, release = copy.deepcopy(INVENTORY), copy.deepcopy(RELEASE)
            environment = "int"
            if change == "environment": environment = "other"
            if change == "local": inventory["environments"][environment]["server"] = "https://kubernetes.default.svc"
            if change == "namespace": inventory["environments"][environment]["namespace"] = "infra"
            if change == "digest": release["images"]["swirlapp-user"]["digest"] = "latest"
            if change == "registry": release["images"]["swirlapp-user"]["repository"] = "registry.other.test/project/swirlapp-user"
            with self.subTest(change=change), self.assertRaises(ValueError):
                RENDER.render(self.root, inventory, environment, release, "a" * 40)
        self.assertFalse((self.root / "infra/environments").exists())

    def test_snapshot_manifest_only_renders_for_int_and_cannot_impersonate_a_release(self):
        snapshot = {**copy.deepcopy(RELEASE), "kind": "snapshot", "sourceBranch": "feature/é;$(example)",
                    "releaseVersion": "snapshot-" + RELEASE["sourceRevision"] + "-" + RELEASE["pipelineId"]}
        RENDER.render(self.root, INVENTORY, "int", snapshot, "a" * 40)
        for environment in ("uat", "prod"):
            with self.subTest(environment=environment), self.assertRaisesRegex(ValueError, "Snapshots can only deploy to int"):
                RENDER.render(self.root, INVENTORY, environment, snapshot, "a" * 40)
        snapshot["kind"] = "release"
        with self.assertRaisesRegex(ValueError, "canonical major.minor.patch"):
            RENDER.render(self.root, INVENTORY, "prod", snapshot, "a" * 40)
        snapshot["kind"] = "snapshot"
        snapshot["releaseVersion"] = "snapshot-latest"
        with self.assertRaisesRegex(ValueError, "exact source"):
            RENDER.render(self.root, INVENTORY, "int", snapshot, "a" * 40)

    def test_api_docs_switch_reaches_backend_and_web_configuration(self):
        environment = self.root / "infra/environments/prod"
        metadata = RENDER.render(self.root, INVENTORY, "prod", RELEASE, "a" * 40)
        self.assertIn("APP_API_DOCS_ENABLED=true\n", (environment / "backend-runtime.properties").read_text())
        self.assertNotIn("apiDocsEnabled", metadata["runtimeConfig"])
        settings = json.loads((environment / "settings.json").read_text())
        self.assertTrue(settings["apiDocs"])
        settings["apiDocs"] = False
        (environment / "settings.json").write_text(json.dumps(settings))
        for _ in range(2):
            metadata = RENDER.render(self.root, INVENTORY, "prod", RELEASE, "a" * 40)
            self.assertIn("APP_API_DOCS_ENABLED=false\n", (environment / "backend-runtime.properties").read_text())
            self.assertIs(False, json.loads((environment / "web-runtime-config.json").read_text())["apiDocsEnabled"])
            self.assertIs(False, metadata["runtimeConfig"]["apiDocsEnabled"])
        (environment / "settings.json").write_text(json.dumps({"apiDocs": "no"}))
        with self.assertRaisesRegex(ValueError, "apiDocs must be boolean"):
            RENDER.render(self.root, INVENTORY, "prod", RELEASE, "a" * 40)

    def test_explicit_legacy_database_adoption_survives_production_rerenders(self):
        settings = self.root / "infra/environments/prod/settings.json"
        settings.parent.mkdir(parents=True)
        settings.write_text(json.dumps({"databaseName": "swirl_demo_app_db"}))
        for _ in range(2):
            metadata = RENDER.render(self.root, INVENTORY, "prod", RELEASE, "a" * 40)
            self.assertEqual("swirl_demo_app_db", metadata["databaseName"])
            self.assertIn("DB_NAME=swirl_demo_app_db\n", (settings.parent / "backend-runtime.properties").read_text())
            self.assertEqual("swirl_demo_app_db", json.loads(settings.read_text())["databaseName"])
        for environment, database in (("int", "swirl_demo_app_db"), ("uat", "swirl_demo_app_prod"), ("prod", "anotherdb")):
            target = self.root / f"infra/environments/{environment}/settings.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps({"databaseName": database}))
            with self.subTest(environment=environment), self.assertRaisesRegex(ValueError, "databaseName"):
                RENDER.render(self.root, INVENTORY, environment, RELEASE, "a" * 40)


if __name__ == "__main__":
    unittest.main()
