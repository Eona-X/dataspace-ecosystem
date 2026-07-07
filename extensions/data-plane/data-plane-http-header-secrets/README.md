# Data Plane HTTP Header Secrets

EDC extension that lets a `HttpDataAddress` inject **multiple** Vault-backed secrets as HTTP headers on outbound data-plane requests, instead of the single header supported natively by EDC.

## Table of Contents

- [Overview](#overview)
- [Problem it Solves](#problem-it-solves)
- [How It Works](#how-it-works)
- [Architecture](#architecture)
- [Usage](#usage)
- [Error Handling](#error-handling)
- [Logging](#logging)
- [Wiring / Deployment](#wiring--deployment)
- [Testing](#testing)
- [Functional / Live Testing](#functional--live-testing)

## Overview

Eclipse EDC's HTTP data-plane sink/source can already attach one authentication header to a request, resolved from the Vault via the `authKey` / `secretName` (or legacy `authCode`) properties on a `HttpDataAddress`. This extension adds a second, complementary mechanism: any number of `header-secret:<header-name>` properties on the address, each pointing to a Vault secret alias, are resolved and added as HTTP headers on the request.

It does not replace or modify the built-in mechanism (`BaseCommonHttpParamsDecorator`, upstream EDC code) — both can be used on the same address at the same time, since this extension registers as an **additional** decorator.

## Problem it Solves

Some external APIs require more than one secret-backed header on the same call — e.g. an `Authorization: Bearer <token>` header **and** a `x-api-key: <key>` header, both needing to come from the Vault rather than being hardcoded in the Data Address. EDC's stock mechanism only supports one.

## How It Works

1. A provider creates an `HttpDataAddress` (source or sink) with one or more properties of the form:
   ```
   header-secret:<Header-Name> = <vault-secret-alias>
   ```
2. At transfer time, EDC's `HttpRequestParamsProvider` invokes every registered `HttpParamsDecorator`, including `HeaderSecretParamsDecorator`.
3. For each `header-secret:*` property found on the address, the decorator resolves `<vault-secret-alias>` via the `Vault` SPI (`vault.resolveSecret(alias)`) and, on success, sets the header `<Header-Name>` to the resolved value on the outgoing HTTP request.
4. If **any** alias cannot be resolved (the Vault returns `null`), **no** header from this decorator is applied and the whole request is failed fast — see [Error Handling](#error-handling).

## Architecture

The extension follows the standard EDC SPI / Core / Extension separation, purely additive on top of upstream `Connector` code (no modification to it):

```
data-plane-http-header-secrets/
├── build.gradle.kts
├── src/main/java/.../headersecrets/
│   ├── HeaderSecretParamsDecorator.java   # HttpParamsDecorator: resolution + aggregation logic
│   └── HeaderSecretExtension.java         # ServiceExtension: injects HttpRequestParamsProvider + Vault,
│                                           # registers the decorator as source and sink
├── src/main/resources/META-INF/services/
│   └── org.eclipse.edc.spi.system.ServiceExtension   # ServiceLoader entry, discovered at boot
└── src/test/java/.../headersecrets/
    └── HeaderSecretParamsDecoratorTest.java
```

Dependencies (`build.gradle.kts`): `edc.spi.dpf.http` (for `HttpParamsDecorator`/`HttpDataAddress`/`HttpRequestParams`) and `edc.spi.core` (for `Vault`, `Monitor`, `EdcException`).

## Usage

Add one property per header you want injected, prefixed with `header-secret:`:

```json
{
  "type": "HttpData",
  "baseUrl": "https://api.example.com/data",
  "header-secret:Authorization": "provider-bearer-token-alias",
  "header-secret:x-api-key": "provider-api-key-alias"
}
```

At transfer time this resolves `provider-bearer-token-alias` and `provider-api-key-alias` from the Vault and sends the request with:

```
Authorization: <resolved bearer token>
x-api-key: <resolved api key>
```

Any number of `header-secret:*` properties can be present on the same address, and they can be freely combined with EDC's stock mechanism mentioned in [Overview](#overview).

## Error Handling

The decorator uses a **fail-fast, aggregated** strategy:

- Every `header-secret:*` property on the address is attempted, regardless of earlier failures — the Vault is queried for every alias.
- Aliases that fail to resolve (Vault returns `null`) are collected, not thrown immediately.
- If at least one alias failed, **no header is applied at all** (not even the ones that resolved successfully) and a single `EdcException` is thrown, listing every unresolved alias and its target header name.
- If all aliases resolve, every corresponding header is applied to the request.

This avoids partial, inconsistent header sets on the outgoing request, and avoids a support cycle of "fix one alias, retry, discover the next missing one" — all missing secrets are reported in the first attempt.

## Logging

The decorator logs through the injected `Monitor`:

- **`severe`** — one line per `header-secret:*` property whose Vault alias could not be resolved, before the aggregated `EdcException` is thrown. Includes the `DataFlowStartMessage` id, the header name, and the missing alias.
- **`debug`** — one line per successfully resolved header, including the `DataFlowStartMessage` id, the header name, and the alias used. The resolved secret **value** itself is never logged.

## Wiring / Deployment

The module is included in the Gradle build (`settings.gradle.kts`) and pulled into the data-plane launcher as a `runtimeOnly` dependency:

```kotlin
// launchers/data-plane/data-plane-base/build.gradle.kts
runtimeOnly(project(":extensions:data-plane:data-plane-http-header-secrets"))
```

Being on the runtime classpath is sufficient — the `ServiceLoader` entry registers `HeaderSecretExtension` automatically with the EDC runtime; no further configuration is required.

## Testing

`HeaderSecretParamsDecoratorTest` covers, using a mocked `Vault` and `Monitor`:

- a single `header-secret:*` property resolving to a header,
- multiple `header-secret:*` properties resolving to multiple headers,
- a single missing alias throwing `EdcException` and logging a `severe` entry, with no header applied,
- several missing aliases: every alias is still attempted, all of them are named in the exception message, and one `severe` log is emitted per missing alias,
- an address with no `header-secret:*` property at all leaves the request params unaffected.

Run just this module's checks:

```bash
./gradlew :extensions:data-plane:data-plane-http-header-secrets:test :extensions:data-plane:data-plane-http-header-secrets:checkstyleMain
```

## Functional / Live Testing

`HeaderSecretParamsDecoratorTest` proves the decorator's *logic* in isolation (mocked `Vault`,
mocked `Monitor`, no real HTTP call). It does not prove that the extension is actually wired
into the running data-plane, that it resolves against a *real* HashiCorp Vault, or that the
resulting header genuinely lands on the outgoing HTTP request. That gap was closed with a
live functional pass against a full local dataspace deployment (provider + consumer connectors,
identity-hub, real Vault, real data-plane), documented here for reproducibility.

### Reasoning

1. **Confirm the feature is actually deployed.** Before testing anything, checked that the
   running provider/consumer data-plane images actually contain the compiled decorator:
   ```bash
   kubectl exec <data-plane-pod> -- sh -c 'unzip -l /app/dataplane.jar 2>/dev/null | grep -i headersecret'
   ```
   Confirmed `HeaderSecretParamsDecorator.class` and `HeaderSecretExtension.class` present in
   the fat jar.

2. **Find a way to observe the resolved header on a real outgoing request.** The obvious idea —
   point a `header-secret:*` asset at the existing `/api/provider/oauth2data` test endpoint and
   inspect the error body when the header is a dummy value — doesn't work: EDC's HTTP-pull
   data-plane wraps any non-2xx response from the source into a generic
   `"Received code transferring HTTP data: <code> - <reason>"` message
   (see `LocalEndToEndTests#transfer_failure`), swallowing the original response body. So a
   failing call can't be used to inspect what header value was actually received — only a
   **successful** (200) response body can be trusted to reflect it.

3. **Add a minimal header-echo endpoint to the test backend.** Extended
   `system-tests/backend-service-provider`'s `ProviderBackendApiController` with a
   `GET /provider/headers` endpoint that returns every received HTTP header as JSON
   (`HttpHeaders` injected via `@Context`). This is a test-fixture-only change, not part of the
   shipped extension:
   ```java
   @Path("/headers")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public Map<String, String> getHeaders(@Context HttpHeaders headers) {
       return headers.getRequestHeaders().entrySet().stream()
               .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.join(",", entry.getValue())));
   }
   ```
   Rebuilt and reloaded just that module into the local kind cluster:
   ```bash
   ./gradlew :system-tests:backend-service-provider:dockerize
   docker tag localhost/local/backend-service-provider:latest backend-service-provider:latest
   kind load docker-image backend-service-provider:latest --name dse-cluster
   kubectl delete pod -l app=provider-backend   # picks up the new image on restart
   ```
   Verified directly with a throwaway pod before writing any test:
   ```bash
   kubectl run curltest --image=curlimages/curl:latest --restart=Never --      curl -s -H "X-Test-Header: hello" http://provider-backend:8080/api/provider/headers
   # {"X-Test-Header":"hello", ...}
   ```

4. **Reuse existing test scaffolding instead of hand-rolling contract negotiation.** The
   dataspace's participants (provider/consumer) were already fully onboarded (DIDs, VCs) from a
   prior full `EndToEndTest` run. Re-running that entire suite would have re-triggered its known
   non-idempotency (fixed-ID attestations conflicting with `409`). Since onboarding trust is
   server-side state, not per-JVM-run state, a new, separate test class was added instead —
   `HeaderSecretFunctionalTest` — extending `AbstractEndToEndTests` (the base class holding
   `negotiationContractAndStartTransfer`, `TEST_TIMEOUT`, etc.) with its own minimal
   `@BeforeAll` that only creates *new* Vault secrets, assets, and contract definitions
   (`PROVIDER.createSecret` / `PROVIDER.createEntry`, same helpers the rest of the suite uses),
   without touching onboarding at all.

### Scenarios covered

Each scenario is a real asset served through `provider-backend`'s `/headers` endpoint, pulled
through a real contract negotiation + `HttpData-PULL` transfer, mirroring the unit test list:

| Scenario | `header-secret:*` setup | Expected result |
|---|---|---|
| Single valid alias | one property, resolves | `200`, response contains the resolved header |
| Multiple valid aliases | two properties, both resolve | `200`, response contains both headers |
| No `header-secret:*` property | none | `200`, response contains neither custom header |
| Single missing alias | one property, alias absent from Vault | transfer fails (`500`) |
| Multiple missing aliases | two properties, both absent | transfer fails (`500`) |
| Mixed valid + missing | one resolves, one doesn't | transfer fails (`500`) — proves the fail-fast **aggregate** rule live: a single missing alias voids the whole header set, not just its own header |

### Result

```bash
./gradlew :system-tests:runner:test --tests "org.eclipse.edc.test.system.HeaderSecretFunctionalTest" -DincludeTags="EndToEndTest"
```

```
Results: SUCCESS (6 tests, 6 passed, 0 failed, 0 skipped)
```

All six scenarios passed against the real deployment, confirming the decorator behaves
identically live to what the mocked unit tests already asserted — the remaining risk the unit
tests couldn't cover (extension wiring, real Vault integration, real HTTP header delivery) is
now closed.
