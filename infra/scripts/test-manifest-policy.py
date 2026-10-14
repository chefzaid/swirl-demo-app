#!/usr/bin/env python3
"""Exercise the workload policy against compliant and non-compliant pod templates."""
import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("check_manifest_policy", Path(__file__).with_name("check-manifest-policy.py"))
policy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(policy)

CONTAINER = {
    "name": "app",
    "image": "registry.example.test:5050/team/app@sha256:" + "a" * 64,
    "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                        "capabilities": {"drop": ["ALL"]}},
    "resources": {"requests": {"cpu": "100m", "memory": "64Mi"}, "limits": {"memory": "128Mi"}},
    "livenessProbe": {"httpGet": {"path": "/live", "port": 8080}},
    "readinessProbe": {"httpGet": {"path": "/ready", "port": 8080}},
}
DEPLOYMENT = {
    "kind": "Deployment", "metadata": {"name": "app"},
    "spec": {"template": {"metadata": {"labels": {"app": "app"}}, "spec": {
        "automountServiceAccountToken": False,
        "securityContext": {"runAsNonRoot": True, "seccompProfile": {"type": "RuntimeDefault"}},
        "containers": [CONTAINER]}}},
}


def deployment(change):
    resource = copy.deepcopy(DEPLOYMENT)
    change(resource["spec"]["template"]["spec"])
    return resource


class ManifestPolicyTest(unittest.TestCase):
    def test_accepts_the_hardened_baseline_and_ignores_other_kinds(self):
        self.assertEqual([], list(policy.violations([DEPLOYMENT, {"kind": "Service", "metadata": {"name": "app"}}, None])))

    def test_reports_each_weakened_setting(self):
        cases = {
            "runAsNonRoot": lambda pod: pod["securityContext"].pop("runAsNonRoot"),
            "seccomp": lambda pod: pod["securityContext"].pop("seccompProfile"),
            "automountServiceAccountToken": lambda pod: pod.pop("automountServiceAccountToken"),
            "hostNetwork": lambda pod: pod.update(hostNetwork=True),
            "allowPrivilegeEscalation": lambda pod: pod["containers"][0]["securityContext"].pop("allowPrivilegeEscalation"),
            "readOnlyRootFilesystem": lambda pod: pod["containers"][0]["securityContext"].update(readOnlyRootFilesystem=False),
            "privileged": lambda pod: pod["containers"][0]["securityContext"].update(privileged=True),
            "drop ALL": lambda pod: pod["containers"][0]["securityContext"].pop("capabilities"),
            "UID 0": lambda pod: pod["containers"][0]["securityContext"].update(runAsUser=0),
            "request cpu": lambda pod: pod["containers"][0]["resources"]["requests"].pop("cpu"),
            "limit memory": lambda pod: pod["containers"][0]["resources"].pop("limits"),
            "pin an image": lambda pod: pod["containers"][0].update(image="registry.example.test:5050/team/app:latest"),
            "livenessProbe": lambda pod: pod["containers"][0].pop("livenessProbe"),
        }
        for expected, change in cases.items():
            with self.subTest(expected):
                found = list(policy.violations([deployment(change)]))
                self.assertEqual(1, len(found), found)
                self.assertIn(expected, found[0])

    def test_untagged_images_are_rejected(self):
        found = list(policy.violations([deployment(lambda pod: pod["containers"][0].update(image="registry:5050/app"))]))
        self.assertEqual(["Deployment/app container app: must pin an image tag or digest"], found)

    def test_jobs_need_no_probes_but_init_containers_are_hardened(self):
        job = deployment(lambda pod: pod.update(initContainers=[{**copy.deepcopy(CONTAINER), "name": "init", "securityContext": {}}]))
        job["kind"] = "Job"
        for container in job["spec"]["template"]["spec"]["containers"]:
            container.pop("livenessProbe")
        found = list(policy.violations([job]))
        self.assertTrue(found)
        self.assertTrue(all("container init:" in message for message in found), found)

    def test_shipped_network_policies_must_bound_every_workloads_egress(self):
        def network_policy(types, selector):
            return {"kind": "NetworkPolicy", "metadata": {"name": "policy"},
                    "spec": {"podSelector": selector, "policyTypes": types}}
        app = {"matchExpressions": [{"key": "app", "operator": "In", "values": ["app"]}]}
        other = {"matchLabels": {"app": "other"}}
        self.assertEqual([], list(policy.violations([DEPLOYMENT])))
        self.assertEqual([], list(policy.violations([DEPLOYMENT, network_policy(["Egress"], app)])))
        self.assertEqual([], list(policy.violations([DEPLOYMENT, network_policy(["Ingress", "Egress"], {})])))
        for types, selector in ((["Ingress"], app), (["Egress"], other)):
            with self.subTest(types=types, selector=selector):
                self.assertEqual(["Deployment/app: pod must be selected by an egress NetworkPolicy"],
                                 list(policy.violations([DEPLOYMENT, network_policy(types, selector)])))


if __name__ == "__main__":
    unittest.main()
