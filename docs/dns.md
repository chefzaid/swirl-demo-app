# Public DNS

One central platform serves `int`, `uat` and `prod` application environments on
the same cluster or optional remote clusters.
Swirl Demo App owns each environment's exact hostname, Ingress routes and browser-client
redirects. The platform owns cluster registration, the managed Cloudflare zone,
certificate preparation and private connectivity.

## Direct ingress

The central `infra/deployment-environments` ConfigMap records each environment's
`domain`, `hostnameSuffix`, `ingressAddress` and TLS Secret. Onboarding uses the app label from
`infra/environments/<env>/settings.json` to reconcile the selected proxied A, AAAA or CNAME record:

| Environment | Example hostname | Destination |
| --- | --- | --- |
| `int` | `demo-int.example.com` | Registered integration ingress address. |
| `uat` | `demo-uat.example.com` | Registered acceptance ingress address. |
| `prod` | `demo.example.com` | Registered production ingress address. |

The table uses the `suffix` hostname style; `nested` remains available for
installations with deeper TLS coverage. All three addresses can be identical. The hostname selects the application's
Ingress in its environment namespace; each environment keeps separate workloads.

`appSubdomain: "@"` selects `int.example.com` or `uat.example.com` for those
environments and the root domain for production. Existing DNS ownership must be
resolved before adopting a hostname already used by another application, including
the production website. Production cannot claim another environment's domain.

Use central [onboarding](deployment.md#add-or-reconfigure-this-repository) to
reconcile the configured hosts. Confirm the target in the central inventory;
do not discover an application address from the central platform's Traefik
Service. The generated environment Ingress must use the hostname and TLS policy
of its registered target.

## Certificates and changes

The Cloudflare token needs Zone Read, DNS Edit, and SSL and Certificates Edit
for the managed parent zone. The certificate permission covers Origin CA issuance
and [certificate coverage inspection](https://developers.cloudflare.com/api/resources/ssl/subresources/certificate_packs/methods/list/).
The `suffix` style uses one-level subdomains covered by the parent zone wildcard.
On the shared cluster, these routes automatically use Traefik's central default
certificate; its private key stays in the platform namespace. Application
Ingresses specify TLS hosts without copying that certificate into their namespace.
Only the optional `nested` style needs additional coverage for deeper names:
pre-issue Advanced/Custom edge coverage or reuse existing Total TLS coverage.
Enabling Total TLS alone needs DNS first and cannot pass the first-publication
guard. See the platform's
[Cloudflare guide](https://github.com/chefzaid/swirl-cloud/blob/main/docs/dns.md#cloudflare).

Review conflicting A/AAAA/CNAME records for the exact hostname and preserve
unrelated MX/TXT records. Changing the app hostname does not automatically delete
its old record. Verify the replacement route, runtime configuration and both
API health routes before retiring the old hostname:

```sh
curl --fail https://demo-int.example.com/runtime-config.json
curl --fail https://demo-int.example.com/health/user
curl --fail https://demo-int.example.com/health/order
```

## HA ingress

The environment's registered ingress address must reach its available application
nodes before enabling the [HA workload profile](deployment.md#future-multi-node-ha-profile).
The central platform's own Cloudflare Tunnel is not a remote-cluster target.
A separately designed environment Tunnel or load balancer needs its own routing
and certificate validation; no central Tunnel checkpoint is reused implicitly.

## Central reconciliation

Rerun the platform's `add-repos.sh` to reconcile Swirl Demo App's declared hosts against
registered environment targets. Keep DNS and browser-client changes together;
deployment verifies that the selected public route serves its expected runtime
identity configuration.
