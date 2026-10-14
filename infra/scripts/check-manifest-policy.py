#!/usr/bin/env python3
"""Enforce the workload security and operability baseline on rendered Kustomize output.

Usage: check-manifest-policy.py [KUSTOMIZE_DIR ...]
Without arguments it checks the base, the HA overlay and every committed environment.
"""
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKLOADS = {"Deployment", "StatefulSet", "DaemonSet", "Job"}


def pod_spec(resource):
    if resource.get("kind") == "CronJob":
        return resource["spec"]["jobTemplate"]["spec"]["template"]["spec"]
    if resource.get("kind") in WORKLOADS:
        return resource["spec"]["template"]["spec"]
    return None


def container_violations(container, long_running):
    context = container.get("securityContext") or {}
    resources = container.get("resources") or {}
    image = container.get("image", "")
    name = image.rsplit("/", 1)[-1]
    checks = [
        (context.get("allowPrivilegeEscalation") is False, "must set allowPrivilegeEscalation: false"),
        (context.get("readOnlyRootFilesystem") is True, "must set readOnlyRootFilesystem: true"),
        (not context.get("privileged"), "must not be privileged"),
        ("ALL" in (context.get("capabilities") or {}).get("drop", []), "must drop ALL capabilities"),
        (context.get("runAsUser") != 0, "must not run as UID 0"),
        ({"cpu", "memory"} <= set(resources.get("requests") or {}), "must request cpu and memory"),
        ("memory" in (resources.get("limits") or {}), "must limit memory"),
        ("@sha256:" in image or ":" in name and not name.endswith(":latest"), "must pin an image tag or digest"),
    ]
    if long_running:
        checks += [(container.get(probe), f"must define a {probe}") for probe in ("livenessProbe", "readinessProbe")]
    return [message for passed, message in checks if not passed]


def violations(resources):
    for resource in resources:
        spec = pod_spec(resource or {})
        if spec is None:
            continue
        where = f"{resource['kind']}/{resource['metadata']['name']}"
        context = spec.get("securityContext") or {}
        if context.get("runAsNonRoot") is not True:
            yield f"{where}: pod must set runAsNonRoot: true"
        if (context.get("seccompProfile") or {}).get("type") not in ("RuntimeDefault", "Localhost"):
            yield f"{where}: pod must use the RuntimeDefault or a Localhost seccomp profile"
        if spec.get("automountServiceAccountToken") is not False:
            yield f"{where}: pod must disable automountServiceAccountToken"
        for flag in ("hostNetwork", "hostPID", "hostIPC"):
            if spec.get(flag):
                yield f"{where}: pod must not use {flag}"
        long_running = resource["kind"] in ("Deployment", "StatefulSet", "DaemonSet")
        for container in spec.get("initContainers", []) + spec.get("containers", []):
            for message in container_violations(container, long_running and container in spec.get("containers", [])):
                yield f"{where} container {container['name']}: {message}"


def render(directory):
    output = subprocess.run(["kubectl", "kustomize", str(directory)], check=True, capture_output=True, text=True).stdout
    return list(yaml.safe_load_all(output))


def main(arguments):
    directories = [Path(argument) for argument in arguments] or [
        ROOT / "infra/k8s", ROOT / "infra/overlays/ha", *sorted((ROOT / "infra/environments").glob("*/"))]
    failures = [f"{directory}: {message}" for directory in directories for message in violations(render(directory))]
    print("\n".join(failures) or f"Workload policy passed for {len(directories)} Kustomize targets.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
