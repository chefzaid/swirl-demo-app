#!/usr/bin/env python3
"""Reconcile this repository's production OIDC client in the existing realm."""

import argparse
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


CLIENT_FILE = Path(__file__).resolve().parents[1] / "keycloak/production-client.json"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class Keycloak:
    def __init__(self, server):
        self.server = server.rstrip("/")
        self.token = None
        # Keep administrator credentials off environment-configured HTTP proxies.
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, body=None, *, form=False):
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None
        if body is not None:
            data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
            headers["Content-Type"] = (
                "application/x-www-form-urlencoded" if form else "application/json"
            )
        request = urllib.request.Request(self.server + path, data, headers, method=method)
        try:
            with self.http.open(request, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            # Error bodies can contain identity information; do not print them.
            error.close()
            raise RuntimeError(f"Keycloak returned HTTP {error.code} for {method} {path}") from None

    def login(self, username, password):
        response = self.request("POST", "/realms/master/protocol/openid-connect/token", {
            "client_id": "admin-cli", "grant_type": "password",
            "username": username, "password": password,
        }, form=True)
        self.token = response["access_token"]


def reconcile_scopes(api, client_path, scope_ids, desired):
    wanted = {
        "default": set(desired.get("defaultClientScopes", [])),
        "optional": set(desired.get("optionalClientScopes", [])),
    }

    def assignments(mode):
        return {item["name"]: item["id"] for item in
                api.request("GET", f"{client_path}/{mode}-client-scopes")}

    current = {mode: assignments(mode) for mode in wanted}
    # Retain the previous compact-token contract: realm management roles do
    # not belong in every browser token. Other operator-added scopes survive.
    excluded_defaults = {"roles"} - wanted["default"]
    for mode, other in [("default", "optional"), ("optional", "default")]:
        remove = set(current[mode]) & wanted[other]
        if mode == "default":
            remove |= set(current[mode]) & excluded_defaults
        for name in sorted(remove):
            api.request("DELETE", f"{client_path}/{mode}-client-scopes/" +
                        urllib.parse.quote(current[mode][name], safe=""))
    for mode in wanted:
        for name in sorted(wanted[mode] - set(current[mode])):
            api.request("PUT", f"{client_path}/{mode}-client-scopes/" +
                        urllib.parse.quote(scope_ids[name], safe=""))

    actual = {mode: set(assignments(mode)) for mode in wanted}
    if (not wanted["default"] <= actual["default"]
            or not wanted["optional"] <= actual["optional"]
            or wanted["default"] & actual["optional"]
            or wanted["optional"] & actual["default"]
            or excluded_defaults & actual["default"]):
        raise RuntimeError("Keycloak client scope assignments did not converge")


def reconcile(api, realm, desired):
    realm_path = "/admin/realms/" + urllib.parse.quote(realm, safe="")
    api.request("GET", realm_path)  # The shared realm must already exist.
    scope_ids = {scope["name"]: scope["id"] for scope in
                 api.request("GET", realm_path + "/client-scopes")}
    defaults = set(desired.get("defaultClientScopes", []))
    optional = set(desired.get("optionalClientScopes", []))
    if defaults & optional:
        raise RuntimeError("A client scope cannot be both default and optional")
    missing = (defaults | optional) - set(scope_ids)
    if missing:
        raise RuntimeError("Shared Keycloak client scopes are missing: " + ", ".join(sorted(missing)))
    clients_path = realm_path + "/clients"
    query = clients_path + "?" + urllib.parse.urlencode({"clientId": desired["clientId"]})
    matches = api.request("GET", query)
    if not matches:
        api.request("POST", clients_path, desired)
        matches = api.request("GET", query)
    matches = [client for client in matches if client["clientId"] == desired["clientId"]]
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one matching Keycloak client")
    client_id = matches[0]["id"]
    client_path = clients_path + "/" + urllib.parse.quote(client_id, safe="")
    # Retain the existing client UUID so sessions and references survive takeover.
    # Client PUT does not update scope assignments in Keycloak 26; use their
    # dedicated endpoints for adoption and subsequent configuration changes.
    body = {key: value for key, value in desired.items()
            if key not in {"protocolMappers", "defaultClientScopes", "optionalClientScopes"}}
    api.request("PUT", client_path, {**body, "id": client_id})
    reconcile_scopes(api, client_path, scope_ids, desired)
    mapper_path = client_path + "/protocol-mappers/models"
    existing_mappers = api.request("GET", mapper_path)
    for mapper in desired.get("protocolMappers", []):
        matches = [item for item in existing_mappers if item["name"] == mapper["name"]]
        if len(matches) > 1:
            raise RuntimeError("Duplicate managed Keycloak protocol mapper")
        if matches:
            mapper_id = matches[0]["id"]
            api.request("PUT", mapper_path + "/" + urllib.parse.quote(mapper_id, safe=""),
                        {**mapper, "id": mapper_id})
        else:
            api.request("POST", mapper_path, mapper)
    return client_id


@contextmanager
def forward_keycloak(namespace):
    # kubectl selects a free port and binds exclusively to IPv4 loopback.
    process = subprocess.Popen([
        "kubectl", "-n", namespace, "port-forward", "--address=127.0.0.1",
        "service/keycloak", ":8080",
    ], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Keycloak port-forward exited before becoming ready")
                if not selector.select(timeout=1):
                    continue
                line = process.stdout.readline()
                match = re.search(r"Forwarding from 127\.0\.0\.1:(\d+) ->", line)
                if match:
                    yield "http://127.0.0.1:" + match[1] + "/auth"
                    return
            raise RuntimeError("Timed out opening the Keycloak port-forward")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        process.stdout.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--realm", default="swirl")
    parser.add_argument("--namespace", default=os.environ.get("INFRA_NAMESPACE", "infra"))
    parser.add_argument("--render", action="store_true", help="Print the public client configuration only")
    args = parser.parse_args()
    desired = json.loads(CLIENT_FILE.read_text())
    if args.render:
        print(json.dumps(desired, indent=2))
        return
    secret = json.loads(subprocess.run([
        "kubectl", "-n", args.namespace, "get", "secret", "keycloak-admin-secret", "-o", "json",
    ], check=True, capture_output=True, text=True, timeout=30).stdout)["data"]
    username = base64.b64decode(secret["KC_BOOTSTRAP_ADMIN_USERNAME"], validate=True).decode()
    password = base64.b64decode(secret["KC_BOOTSTRAP_ADMIN_PASSWORD"], validate=True).decode()
    with forward_keycloak(args.namespace) as server:
        api = Keycloak(server)
        api.login(username, password)
        reconcile(api, args.realm, desired)
    print(f"Configured {desired['clientId']} in realm {args.realm}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Unable to configure the Swirl Demo App Keycloak client: {error}", file=sys.stderr)
        sys.exit(1)
