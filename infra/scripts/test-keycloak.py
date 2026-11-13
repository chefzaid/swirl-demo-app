#!/usr/bin/env python3
"""Offline checks for production client takeover and credential handling."""

from copy import deepcopy
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import threading
import unittest


spec = importlib.util.spec_from_file_location(
    "configure_keycloak", Path(__file__).with_name("configure-keycloak.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeKeycloak:
    def __init__(self, existing=False):
        self.clients = [{"id": "other-client", "clientId": "unrelated-app"}]
        self.mappers = []
        self.calls = []
        self.scopes = {name: f"scope-{name}" for name in
                       ["profile", "email", "web-origins", "acr", "basic", "roles", "groups", "offline_access"]}
        self.assignments = {"default": set(), "optional": set()}
        if existing:
            self.clients.append({"id": "original-uuid", "clientId": "swirlapp-web"})
            self.mappers.append({"id": "original-mapper", "name": "groups", "config": {}})
            self.assignments = {
                "default": {"profile", "roles", "groups"},
                "optional": {"offline_access", "email"},
            }

    def request(self, method, path, body=None):
        self.calls.append((method, path, deepcopy(body)))
        if path.endswith("/swirl") and method == "GET":
            return {"realm": "swirl"}
        if path.endswith("/client-scopes") and method == "GET":
            return [{"name": name, "id": scope_id} for name, scope_id in self.scopes.items()]
        for mode in self.assignments:
            segment = f"/{mode}-client-scopes"
            if segment not in path:
                continue
            if method == "GET":
                return [{"name": name, "id": self.scopes[name]} for name in self.assignments[mode]]
            scope_id = path.rsplit("/", 1)[1]
            name = next(name for name, value in self.scopes.items() if value == scope_id)
            if method == "DELETE":
                self.assignments[mode].remove(name)
                return None
            if method == "PUT":
                other = "optional" if mode == "default" else "default"
                if name in self.assignments[other]:
                    raise AssertionError("Scope must be detached before switching assignment mode")
                self.assignments[mode].add(name)
                return None
        if "?clientId=" in path:
            return deepcopy([item for item in self.clients if item["clientId"] == "swirlapp-web"])
        if path.endswith("/clients") and method == "POST":
            self.clients.append({**deepcopy(body), "id": "created-uuid"})
            self.mappers = [dict(item, id=f"created-mapper-{index}")
                            for index, item in enumerate(body["protocolMappers"])]
            self.assignments = {"default": set(body["defaultClientScopes"]),
                                "optional": set(body["optionalClientScopes"])}
            return None
        if "/protocol-mappers/models" in path:
            if method == "GET":
                return deepcopy(self.mappers)
            if method == "POST":
                self.mappers.append({**deepcopy(body), "id": "new-mapper"})
                return None
            if method == "PUT":
                index = next(i for i, item in enumerate(self.mappers) if item["id"] == body["id"])
                self.mappers[index] = deepcopy(body)
                return None
        if method == "PUT":
            index = next(i for i, item in enumerate(self.clients) if item["id"] == body["id"])
            # Real Keycloak client PUT ignores these fields. Only the scope
            # endpoints above change the effective assignments on an update.
            self.clients[index] = {key: deepcopy(value) for key, value in body.items()
                                   if key not in {"defaultClientScopes", "optionalClientScopes"}}
            return None
        raise AssertionError((method, path))


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.desired = json.loads(module.CLIENT_FILE.read_text())

    def test_create_then_repeat_keeps_one_client_and_each_mapper(self):
        api = FakeKeycloak()
        first = module.reconcile(api, "swirl", self.desired)
        second = module.reconcile(api, "swirl", self.desired)
        self.assertEqual(first, second)
        self.assertEqual(len(api.clients), 2)
        self.assertEqual(len(api.mappers), 2)
        self.assertEqual(api.clients[0], {"id": "other-client", "clientId": "unrelated-app"})
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.calls))

    def test_adopts_existing_client_and_repairs_mapper_without_replacing_identity(self):
        api = FakeKeycloak(existing=True)
        api.mappers.append({"id": "operator-mapper", "name": "operator-owned"})
        self.assertEqual(module.reconcile(api, "swirl", self.desired), "original-uuid")
        self.assertEqual(api.mappers[0]["id"], "original-mapper")
        self.assertEqual(api.mappers[0]["config"]["claim.name"], "groups")
        self.assertEqual(api.mappers[1], {"id": "operator-mapper", "name": "operator-owned"})
        self.assertEqual(api.clients[1]["attributes"]["pkce.code.challenge.method"], "S256")
        self.assertTrue(api.clients[1]["publicClient"])
        self.assertFalse(api.clients[1]["directAccessGrantsEnabled"])
        self.assertFalse(api.clients[1]["implicitFlowEnabled"])
        self.assertNotIn("roles", api.assignments["default"])
        self.assertEqual(api.assignments["default"], set(self.desired["defaultClientScopes"]))
        self.assertEqual(api.assignments["optional"], {"groups", "offline_access"})
        self.assertNotIn("secret", api.clients[1])
        self.assertEqual(api.clients[1]["webOrigins"], self.desired["webOrigins"])
        module.reconcile(api, "swirl", self.desired)
        self.assertEqual(api.assignments["optional"], {"groups", "offline_access"})

    def test_missing_shared_scope_fails_before_mutations(self):
        api = FakeKeycloak()
        del api.scopes["groups"]
        with self.assertRaisesRegex(RuntimeError, "client scopes are missing: groups"):
            module.reconcile(api, "swirl", self.desired)
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))

    def test_scope_readback_rejects_an_acknowledged_but_ineffective_change(self):
        class IneffectiveScopeUpdate(FakeKeycloak):
            def request(self, method, path, body=None):
                if method == "PUT" and path.endswith("/optional-client-scopes/scope-groups"):
                    return None
                return super().request(method, path, body)

        api = IneffectiveScopeUpdate(existing=True)
        with self.assertRaisesRegex(RuntimeError, "did not converge"):
            module.reconcile(api, "swirl", self.desired)

    def test_missing_realm_fails_before_writing(self):
        class MissingRealm(FakeKeycloak):
            def request(self, method, path, body=None):
                if path.endswith("/swirl"):
                    raise RuntimeError("Realm does not exist")
                return super().request(method, path, body)

        api = MissingRealm()
        with self.assertRaisesRegex(RuntimeError, "Realm does not exist"):
            module.reconcile(api, "swirl", self.desired)
        self.assertEqual(api.calls, [])

    def test_ambiguous_client_fails_before_writing(self):
        api = FakeKeycloak(existing=True)
        api.clients.append({"id": "ambiguous-uuid", "clientId": "swirlapp-web"})
        with self.assertRaisesRegex(RuntimeError, "exactly one"):
            module.reconcile(api, "swirl", self.desired)
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))


class HttpTests(unittest.TestCase):
    def test_credentials_are_not_redirected_and_error_body_is_not_reported(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/capture-token")
                else:
                    self.send_response(403)
                self.end_headers()
                self.wfile.write(b'private-identity-details')

            def log_message(self, *_args):
                pass

        with HTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                api = module.Keycloak(f"http://127.0.0.1:{server.server_port}")
                api.token = "test-only-token"
                for path in ["/redirect", "/denied"]:
                    with self.assertRaises(RuntimeError) as failure:
                        api.request("GET", path)
                    self.assertNotIn("private-identity-details", str(failure.exception))
                    self.assertNotIn("test-only-token", str(failure.exception))
                self.assertEqual(requests, ["/redirect", "/denied"])
            finally:
                server.shutdown()
                thread.join()


if __name__ == "__main__":
    unittest.main()
