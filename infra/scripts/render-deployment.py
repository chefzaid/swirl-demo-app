#!/usr/bin/env python3
"""Render one registered environment from a shared release; never copy application source."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse

import yaml


ENVIRONMENTS = ("int", "uat", "prod")
IMAGES = ("swirlapp-user", "swirlapp-order", "swirlapp-web")
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
RESERVED_NAMESPACES = {"default", "infra", "corp", "gitlab-runners", "longhorn-system", "external-secrets"}
SHARED_NAMESPACE = "apps"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def public_string(value, name):
    require(isinstance(value, str) and value and not any(c in value for c in "\r\n\x00"),
            f"Invalid public setting: {name}")
    return value


def domain(value):
    public_string(value, "domain")
    require(len(value) <= 253 and "." in value and all(LABEL.fullmatch(part) for part in value.split(".")),
            "Expected a lowercase DNS domain")
    return value


def target(inventory, environment):
    require(environment in ENVIRONMENTS, "DEPLOYMENT_ENVIRONMENT must be int, uat or prod")
    require(inventory.get("version") == 1, "Unsupported deployment inventory version")
    selected = inventory.get("environments", {}).get(environment)
    require(isinstance(selected, dict), f"Environment {environment} is not registered")
    hostname_style = inventory.get("platform", {}).get("hostnameStyle", "nested")
    require(hostname_style in ("nested", "suffix"), "Hostname style must be nested or suffix")
    hostname_suffix = "-" + environment if hostname_style == "suffix" and environment != "prod" else ""
    require(selected.get("hostnameSuffix", hostname_suffix) == hostname_suffix, "Environment hostname suffix differs from its registered style")
    require(LABEL.fullmatch(selected.get("clusterName", "")) is not None, "Invalid registered cluster name")
    mode = selected.get("mode", "remote")
    require(mode in ("local", "remote"), "Environment mode must be local or remote")
    api = urlparse(selected.get("server", ""))
    require(api.scheme == "https" and api.hostname and not api.username and not api.password
            and api.path in ("", "/") and not api.query and not api.fragment
            and api.hostname not in ("localhost", "127.0.0.1", "::1"),
            "Environment needs its registered HTTPS Kubernetes API")
    if mode == "local":
        require(selected["server"] == "https://kubernetes.default.svc" and selected["clusterName"] == "in-cluster",
                "Local environments must use the shared in-cluster Kubernetes API")
    else:
        require(api.hostname != "kubernetes.default.svc", "A shared-cluster environment must explicitly select local mode")
    namespace = selected.get("namespace", "")
    require(LABEL.fullmatch(namespace) and namespace not in RESERVED_NAMESPACES and not namespace.startswith("kube-"),
            "Environment needs an application namespace")
    require(not (mode == "local" and namespace == SHARED_NAMESPACE and environment != "prod"),
            "Only local prod runs in the shared apps namespace; int and uat need dedicated namespaces")
    store = selected.get("secretStoreName", "vault-backend-" + environment if mode == "local" else "vault-backend")
    require(LABEL.fullmatch(store), "Invalid environment secret store name")
    for other, entry in inventory.get("environments", {}).items():
        if other != environment:
            same_cluster = entry.get("server") == selected["server"] or entry.get("clusterName") == selected["clusterName"]
            require(not same_cluster or mode == "local" and entry.get("mode", "remote") == "local",
                    "Remote environments require distinct registered clusters")
            require(not (same_cluster and entry.get("namespace") == namespace),
                    "Environments on one cluster need distinct namespaces")
    domain(selected.get("domain"))
    ipaddress.ip_network(selected["podCIDR"], strict=False)
    return {**selected, "hostnameSuffix": hostname_suffix, "secretStoreName": store}


def kubectl(*arguments):
    # --request-timeout would bypass in-cluster credentials; bound the process instead.
    result = subprocess.run(["kubectl", *arguments], text=True,
                            capture_output=True, timeout=60)
    require(result.returncode == 0, "Cannot verify the central deployment inventory or Argo CD registration")
    return json.loads(result.stdout)


def verify_live(inventory, environment):
    live = json.loads(kubectl("get", "configmap", "deployment-environments", "-n", "infra", "-o", "json")
                      ["data"]["environments.json"])
    selected = target(live, environment)
    require(live.get("platform") == inventory.get("platform"),
            "Central platform settings differ from this project's onboarding inventory; rerun onboarding")
    previous = inventory.get("environments", {}).get(environment)
    require(previous is None or (all(previous.get(key) == selected[key] for key in ("clusterName", "server", "namespace"))
                                and previous.get("mode", "remote") == selected.get("mode", "remote")),
            "The selected environment was assigned to another cluster or namespace; explicit migration is required")
    # Registration publishes this record only after verifying the actual Argo
    # Secret, target identity and foundation. CI never reads cluster credentials.
    return {**inventory, "environments": {**inventory.get("environments", {}), environment: selected}}


def release_manifest(value, registry, project, environment="int"):
    kind = value.get("kind", "release")
    require(value.get("version") == 1 and kind in ("release", "snapshot"), "Invalid immutable image manifest kind")
    if kind == "snapshot":
        require(environment == "int", "Snapshots can only deploy to int; uat and prod require a finalized release")
        require(value.get("releaseVersion") == f"snapshot-{value.get('sourceRevision')}-{value.get('pipelineId')}",
                "Snapshot identity must contain its exact source and publishing pipeline")
        public_string(value.get("sourceBranch"), "snapshot source branch")
    else:
        require(VERSION.fullmatch(value.get("releaseVersion", "")), "A release must use canonical major.minor.patch")
    require(SHA.fullmatch(value.get("sourceRevision", "")), "Release source must be an exact Git revision")
    require(str(value.get("pipelineId", "")).isdigit(), "Release manifest requires its publishing pipeline")
    require(set(value.get("images", {})) == set(IMAGES), "Release must contain exactly the three Swirl Demo App images")
    for name, image in value["images"].items():
        require(image.get("repository") == f"{registry}/{project}/{name}" and DIGEST.fullmatch(image.get("digest", "")),
                "Release image must use this project's central registry and an immutable digest")
    return value


def settings(root, selected, environment):
    path = root / "infra/environments" / environment / "settings.json"
    value = json.loads(path.read_text()) if path.is_file() else {}
    require(isinstance(value, dict) and not set(value) - {"appSubdomain", "trustedProxyCIDRs", "highAvailability", "databaseName", "apiDocs"},
            "Unsupported environment deployment setting")
    label = value.get("appSubdomain", "demo")
    require(label == "@" or isinstance(label, str) and LABEL.fullmatch(label), "Invalid application subdomain")
    require(environment != "prod" or label not in ("int", "uat"), "Production cannot own another environment's hostname")
    suffix = selected.get("hostnameSuffix", "")
    host = ((environment + "." if suffix else "") + selected["domain"] if label == "@"
            else label + suffix + "." + selected["domain"])
    cidrs = value.get("trustedProxyCIDRs", selected["podCIDR"])
    public_string(cidrs, "trustedProxyCIDRs")
    require(all(part.strip() for part in cidrs.split(",")), "Trusted proxy CIDRs cannot be empty")
    for part in cidrs.split(","):
        ipaddress.ip_network(part.strip(), strict=False)
    require(isinstance(value.get("highAvailability", False), bool), "highAvailability must be boolean")
    api_docs = value.get("apiDocs", True)
    require(isinstance(api_docs, bool), "apiDocs must be boolean")
    database = value.get("databaseName", "swirl_demo_app_" + environment)
    require(database == "swirl_demo_app_" + environment or environment == "prod" and database == "swirl_demo_app_db",
            "databaseName must be this environment's database; only prod can explicitly adopt legacy swirl_demo_app_db")
    return host, cidrs, value.get("highAvailability", False), database, label, api_docs


def output_file(root, name, content):
    path = root / name
    require(not any(part.is_symlink() for part in (path, *path.parents)), "Deployment output cannot contain symlinks")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def render(root, inventory, environment, release, revision, repository_url=None, check_only=False):
    selected = target(inventory, environment)
    require(SHA.fullmatch(revision), "Deployment must pin an exact runtime source revision")
    template = yaml.safe_load((root / "infra/argocd/application.yaml").read_text())
    repository_url = repository_url or template["spec"]["source"]["repoURL"]
    parsed = urlparse(repository_url)
    require(parsed.scheme in ("http", "https") and parsed.hostname and not parsed.username and not parsed.password
            and not parsed.query and not parsed.fragment, "Use the central GitLab repository URL without credentials")
    project = parsed.path.strip("/").removesuffix(".git")
    require(len(project.split("/")) >= 2 and all(re.fullmatch(r"[A-Za-z0-9_.-]+", p) for p in project.split("/")),
            "Invalid central GitLab project path")
    require(not os.environ.get("CI_PROJECT_PATH") or os.environ["CI_PROJECT_PATH"] == project,
            "Application repository does not match the running GitLab project")
    services = selected.get("services", inventory["platform"]["services"])
    namespace = selected["namespace"]
    registry = public_string(services["registry"]["host"], "registry host")
    release_manifest(release, registry, project, environment)
    host, cidrs, ha, database, app_label, api_docs = settings(root, selected, environment)
    central_tls = selected.get("mode", "remote") == "local" and inventory["platform"].get("hostnameStyle", "nested") == "suffix"
    tls = {"hosts": [host]}
    if not central_tls:
        tls["secretName"] = selected.get("tlsSecretName", selected["domain"].replace(".", "-") + "-tls")
    keycloak = services["keycloak"]
    identity_url = public_string(keycloak["url"], "Keycloak URL").rstrip("/")
    require(urlparse(identity_url).scheme == "https", "Browser identity must use HTTPS")
    realm = public_string(keycloak["realm"], "Keycloak realm")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", realm) and realm != "master", "Invalid application realm")
    client = f"swirl-demo-app-{environment}-web"
    web = {"keycloakUrl": identity_url, "keycloakRealm": realm, "keycloakClientId": client}
    if not api_docs:
        # Emitted only when disabled: web images older than this setting reject unknown runtime keys.
        web["apiDocsEnabled"] = False
    metadata = {"environment": environment, "application": f"swirl-demo-app-{environment}", "host": host,
                "repository": repository_url, "project": f"applications-{environment}",
                "url": "https://" + host, "runtimeConfig": web, "revision": revision, "databaseName": database,
                "destination": {"name": selected["clusterName"], "namespace": namespace},
                "images": [release["images"][name]["repository"] + "@" + release["images"][name]["digest"] for name in IMAGES]}
    if check_only:
        return metadata
    backend = {
        "DB_HOST": services["postgres"]["host"], "DB_PORT": str(services["postgres"]["port"]),
        "DB_NAME": database,
        "REDIS_HOST": services["redis"]["host"], "REDIS_PORT": str(services["redis"]["port"]),
        "REDIS_CACHE_PREFIX": f"swirl-demo-app:{environment}:",
        "KAFKA_BOOTSTRAP_SERVERS": services["kafka"]["bootstrapServers"],
        "KAFKA_SECURITY_PROTOCOL": services["kafka"]["securityProtocol"],
        "KAFKA_TOPIC_PREFIX": f"swirl-demo-app.{environment}.",
        "JWT_ISSUER_URI": identity_url + "/realms/" + realm,
        "JWT_JWK_SET_URI": identity_url + "/realms/" + realm + "/protocol/openid-connect/certs",
        "JWT_AUDIENCE": client, "CORS_ALLOWED_ORIGINS": "https://" + host,
        "APP_RATE_LIMIT_TRUSTED_PROXY_CIDRS": cidrs,
        "APP_API_DOCS_ENABLED": str(api_docs).lower(),
    }
    for name, value in backend.items():
        public_string(value, name)
    relative = Path("infra/environments") / environment
    patches = [{"target": {"kind": "Ingress", "name": "swirl-demo-app-ingress"}, "patch": yaml.safe_dump([
        {"op": "replace", "path": "/spec/rules/0/host", "value": host},
        {"op": "replace", "path": "/spec/tls/0/hosts", "value": [host]},
        ({"op": "remove", "path": "/spec/tls/0/secretName"} if central_tls else
         {"op": "replace", "path": "/spec/tls/0/secretName", "value": tls["secretName"]}),
        {"op": "replace", "path": "/metadata/annotations/gethomepage.dev~1href", "value": "https://" + host},
        {"op": "replace", "path": "/metadata/annotations/gethomepage.dev~1name", "value": "Swirl Demo App"},
        {"op": "replace", "path": "/metadata/annotations/gethomepage.dev~1group", "value": f"Applications - {environment.upper()}"},
        {"op": "replace", "path": "/metadata/annotations/traefik.ingress.kubernetes.io~1router.middlewares",
         "value": f"{namespace}-swirl-demo-app-upload-limit@kubernetescrd"},
    ], sort_keys=False)}]
    for service in IMAGES:
        patches.append({"target": {"kind": "Service", "name": service}, "patch": yaml.safe_dump([
            {"op": "replace", "path": "/metadata/annotations/traefik.ingress.kubernetes.io~1service.serverstransport",
             "value": f"{namespace}-swirl-demo-app-backend@kubernetescrd"}], sort_keys=False)})
    patches.append({"target": {"kind": "ExternalSecret"}, "patch": yaml.safe_dump([
        {"op": "replace", "path": "/spec/secretStoreRef/name", "value": selected.get("secretStoreName", "vault-backend")}
    ], sort_keys=False)})
    dashboard_resource = yaml.safe_load((root / "infra/k8s/observability.yaml").read_text())
    dashboard = json.loads(dashboard_resource["data"]["swirl-demo-app-overview.json"].replace(
        r'namespace=\"apps\"', rf'namespace=\"{namespace}\"'))
    dashboard["uid"] = f"swirl-demo-app-{environment}-overview"
    dashboard["title"] = f"Swirl Demo App {environment.upper()} Overview"
    patches.append({"target": {"kind": "ConfigMap", "name": "swirl-demo-app-grafana-dashboard"}, "patch": yaml.safe_dump([
        {"op": "replace", "path": "/data", "value": {f"swirl-demo-app-{environment}-overview.json": json.dumps(dashboard, indent=2) + "\n"}}
    ], sort_keys=False)})
    # Each copy evaluates only its own namespace, so environments sharing one Prometheus never record a series twice.
    rules = (root / "infra/observability/slo-rules.yaml").read_text().replace('namespace="apps"', f'namespace="{namespace}"')
    patches.append({"target": {"kind": "ConfigMap", "name": "swirl-demo-app-prometheus-rules"}, "patch": yaml.safe_dump([
        {"op": "replace", "path": "/data", "value": {f"swirl-demo-app-{environment}-slo-rules.yaml": rules}}
    ], sort_keys=False)})
    if selected.get("mode", "remote") == "local" and namespace != SHARED_NAMESPACE:
        # The platform owns dedicated environment network boundaries; app delivery
        # cannot add a policy that defeats environment isolation. In the shared
        # apps namespace the application ships its own ingress allowance instead.
        patches.append({"target": {"kind": "NetworkPolicy"}, "patch": yaml.safe_dump({
            "apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
            "metadata": {"name": "platform-owned"}, "$patch": "delete"}, sort_keys=False)})
    secrets = list(yaml.safe_load_all((root / "infra/k8s/external-secrets.yaml").read_text()))
    runtime_secret = next(item for item in secrets if item and item.get("kind") == "ExternalSecret"
                          and item["metadata"]["name"] == "swirl-demo-app-runtime-credentials")
    secret_patch = []
    for index, item in enumerate(runtime_secret["spec"]["data"]):
        service = {"DB": "database", "REDIS": "redis", "KAFKA": "kafka"}.get(item["secretKey"].split("_", 1)[0])
        require(service is not None, "Unsupported runtime credential mapping")
        secret_patch.append({"op": "replace", "path": f"/spec/data/{index}/remoteRef/key",
                             "value": f"apps/swirl-demo-app/{environment}/{service}"})
    patches.append({"target": {"kind": "ExternalSecret", "name": "swirl-demo-app-runtime-credentials"},
                    "patch": yaml.safe_dump(secret_patch, sort_keys=False)})
    kustomization = {"apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization", "namespace": namespace,
                    "resources": ["../../overlays/ha" if ha else "../../k8s", "health-ingress.yaml"],
                    "configMapGenerator": [
                        {"name": "swirl-demo-app-backend-config", "behavior": "replace", "envs": ["backend-runtime.properties"],
                         "options": {"annotations": {"argocd.argoproj.io/sync-wave": "-2"}}},
                        {"name": "swirlapp-web-config", "behavior": "replace", "files": ["runtime-config.json=web-runtime-config.json"]}],
                    "images": [{"name": release["images"][name]["repository"], "digest": release["images"][name]["digest"]} for name in IMAGES],
                    "patches": patches}
    app = {"apiVersion": "argoproj.io/v1alpha1", "kind": "Application",
           "metadata": {"name": metadata["application"], "namespace": "infra",
                        "annotations": {"swirl-demo-app.delivery/pipeline": str(os.environ.get("CI_PIPELINE_ID", release["pipelineId"])),
                                        "swirl-demo-app.delivery/source": release["sourceRevision"],
                                        "swirl-demo-app.delivery/kind": release.get("kind", "release")},
                        "labels": {"app.kubernetes.io/name": "swirl-demo-app", "app.kubernetes.io/part-of": "swirl-demo-app",
                                   "swirl-cloud/environment": environment}},
           "spec": {"project": f"applications-{environment}", "source": {"repoURL": repository_url,
                    "targetRevision": revision, "path": str(relative)}, "destination": metadata["destination"],
                    "syncPolicy": template["spec"]["syncPolicy"]}}
    health_ingress = {
        "apiVersion": "networking.k8s.io/v1", "kind": "Ingress", "metadata": {
            "name": "swirl-demo-app-health", "namespace": namespace, "annotations": {
                "argocd.argoproj.io/sync-wave": "10",
                "traefik.ingress.kubernetes.io/router.entrypoints": "websecure",
                "traefik.ingress.kubernetes.io/router.tls": "true",
                "traefik.ingress.kubernetes.io/router.middlewares": f"{namespace}-swirl-demo-app-health@kubernetescrd"}},
        "spec": {"ingressClassName": "traefik", "tls": [tls],
            "rules": [{"host": host, "http": {"paths": [{"path": "/health/" + name, "pathType": "Exact",
                "backend": {"service": {"name": "swirlapp-" + name, "port": {"number": port}}}}
                for name, port in (("user", 8080), ("order", 8081))]}}]}}
    health_middleware = {"apiVersion": "traefik.io/v1alpha1", "kind": "Middleware",
                         "metadata": {"name": "swirl-demo-app-health", "namespace": namespace},
                         "spec": {"replacePath": {"path": "/actuator/health"}}}
    output_file(root, relative / "settings.json", json.dumps({
        "appSubdomain": app_label,
        "trustedProxyCIDRs": cidrs, "highAvailability": ha, "databaseName": database, "apiDocs": api_docs}, indent=2) + "\n")
    output_file(root, relative / "health-ingress.yaml", yaml.safe_dump_all([health_ingress, health_middleware], sort_keys=False))
    output_file(root, relative / "backend-runtime.properties", "# Public environment settings; credentials come from Vault.\n" +
                "".join(f"{key}={value}\n" for key, value in backend.items()))
    output_file(root, relative / "web-runtime-config.json", json.dumps(web, indent=2) + "\n")
    output_file(root, relative / "kustomization.yaml", yaml.safe_dump(kustomization, sort_keys=False))
    output_file(root, Path("infra/argocd") / f"{environment}.yaml", yaml.safe_dump(app, sort_keys=False))
    return metadata


def verify_finalized_release(release):
    """A live render must not bypass GitLab release finalization via direct invocation."""
    if release.get("kind", "release") == "snapshot":
        return
    require(VERSION.fullmatch(release.get("releaseVersion", "")), "Select a canonical release version")
    tag = "v" + release["releaseVersion"]
    def git(*arguments):
        result = subprocess.run(["git", *arguments], text=True, capture_output=True, timeout=60)
        require(result.returncode == 0, "Cannot verify the selected immutable release tag")
        return result.stdout.strip()
    git("fetch", "--quiet", "--no-tags", "origin", f"refs/tags/{tag}:refs/tags/{tag}")
    require(git("cat-file", "-t", "refs/tags/" + tag) == "tag", "Release tag must be annotated")
    revision = git("rev-parse", f"refs/tags/{tag}^{{commit}}")
    require(json.loads(git("show", f"{revision}:infra/releases/{release['releaseVersion']}.json")) == release,
            "Deployment manifest differs from the immutable release tag")
    require(git("rev-list", "--parents", "-n", "1", revision) == revision + " " + release["sourceRevision"]
            and git("show", revision + ":VERSION") == release["releaseVersion"],
            "Release tag does not directly record its published source and version")
    branch = os.environ.get("CI_DEFAULT_BRANCH", "")
    require(branch and git("check-ref-format", "refs/heads/" + branch) == "", "Missing default branch identity")
    git("fetch", "--quiet", "--no-tags", "origin", f"refs/heads/{branch}:refs/remotes/origin/{branch}")
    git("merge-base", "--is-ancestor", revision, "refs/remotes/origin/" + branch)
    token, api, project = (os.environ.get(key, "") for key in ("CI_JOB_TOKEN", "CI_API_V4_URL", "CI_PROJECT_ID"))
    require(token and api and project.isdigit(), "Live release verification requires the GitLab job identity")
    result = subprocess.run(["curl", "--fail", "--silent", "--show-error", "--header", "JOB-TOKEN: " + token,
                             f"{api}/projects/{project}/releases/{tag}"], text=True, capture_output=True, timeout=60)
    require(result.returncode == 0, "GitLab has not finalized this release")
    finalized = json.loads(result.stdout)
    require(finalized.get("tag_name") == tag and finalized.get("commit", {}).get("id") == revision,
            "GitLab has not finalized this exact immutable release")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--environment", required=True, choices=ENVIRONMENTS)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, default=Path.cwd())
    parser.add_argument("--repository-url")
    parser.add_argument("--verify-live", action="store_true", help="Verify the central registration checkpoint without reading credentials")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    try:
        inventory = json.loads(args.inventory.read_text())
        release = json.loads(args.release.read_text())
        if args.verify_live:
            inventory = verify_live(inventory, args.environment)
            verify_finalized_release(release)
        print(json.dumps(render(args.output.resolve(), inventory, args.environment,
                                release, args.revision, args.repository_url, args.check_only)))
    except (ValueError, KeyError, TypeError, StopIteration, OSError, subprocess.TimeoutExpired, yaml.YAMLError) as error:
        parser.exit(1, f"Deployment configuration rejected: {error}\n")


if __name__ == "__main__":
    main()
