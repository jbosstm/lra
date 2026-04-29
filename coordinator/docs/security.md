# LRA Coordinator Security

## Overview

The LRA Coordinator supports optional JWT-based security for two concerns:

1. **Inbound authentication** -- validating Bearer tokens on coordinator API endpoints
   (delegated to the container's MicroProfile JWT implementation)
2. **Outbound token propagation** -- forwarding credentials when the coordinator calls
   participant callbacks (compensate, complete, status, forget, afterLRA)

## Inbound Authentication

Inbound JWT authentication is **not implemented by the coordinator itself**. It is the
responsibility of the deployment container:

- **WildFly**: configure via the Elytron subsystem and `microprofile-jwt-smallrye` extension
- **Quarkus**: configure via `quarkus.smallrye-jwt.*` properties in `application.properties`

The container validates the JWT, populates `SecurityContext`, and makes `JsonWebToken`
available via CDI. The coordinator does not enforce authorization on its endpoints --
access control is left to the container or an external gateway.

## Outbound Token Propagation

When a caller invokes a coordinator endpoint with a Bearer token, that token can be
forwarded to participant callback URIs. This is useful when participants also require
JWT authentication.

### Configuration

```properties
lra.http-client.providers=io.narayana.lra.coordinator.security.JwtTokenClientRequestFilter
```

This registers a `ClientRequestFilter` on all outbound HTTP clients. The same config
key (`lra.http-client.providers`) is used by `RestClientConfig` in the lra-client
module, so one property covers both the coordinator's direct HTTP calls and the
`NarayanaLRAClient`.

### How It Works

1. The container's MicroProfile JWT implementation validates the inbound token and makes
   `JsonWebToken` available via CDI (`@Inject JsonWebToken`).
2. `JwtTokenClientRequestFilter` reads `JsonWebToken.getRawToken()` via CDI injection
   (when instantiated by MicroProfile Rest Client on the request thread).
3. `JwtTokenContext.newClient()` looks up `JsonWebToken` via `CDI.current()` and stores
   the raw token as a client configuration property (surviving async thread dispatch
   for plain JAX-RS clients used by the coordinator internally).
4. The filter adds `Authorization: Bearer <token>` to the outbound request.

Note: the container's security subsystem only handles inbound authentication. Outbound
token propagation to participants is always configured at the application level using
the properties described above.

## Recovery Thread and Service Token

The Narayana recovery thread (`LRARecoveryModule.periodicWorkSecondPass()`) retries
failed participant callbacks periodically. This thread has **no CDI request scope**,
so `JsonWebToken` is not available. Without additional configuration, outbound calls
from recovery lack credentials and will be rejected by auth-protected participants.

### Symptom

LRAs remain stuck in `Closing` or `Cancelling` state indefinitely. The coordinator
log shows repeated retry attempts, and participant logs show 401 rejections.

### Solution: Service Token

The coordinator can read a pre-provisioned service token from a file, classpath
resource, or HTTP endpoint. When `JsonWebToken` is not available (no CDI request scope)
and a service token location is configured, `JwtTokenContext.newClient()` reads the
token from that location.

```properties
lra.security.service-token.location=/var/run/secrets/lra/token
lra.security.service-token.refresh-seconds=300
```

Supported location schemes:

| Scheme | Example | Use Case |
|--------|---------|----------|
| Plain path | `/var/run/secrets/lra/token` | Kubernetes projected volumes |
| `file://` | `file:///opt/tokens/service.jwt` | Filesystem with explicit scheme |
| `classpath://` | `classpath://META-INF/service-token` | Bundled static token (dev/test) |
| `http://` / `https://` | `https://token-service/token` | Token vending service |

The token is cached for `refresh-seconds` (default 300) and then re-read from the
source. This supports external rotation: a sidecar, init container, or Vault Agent
writes a new token to the file before the old one expires, and the coordinator picks
it up on the next refresh.

### Token Resolution Order

When `JwtTokenContext.newClient()` creates an outbound client, the token is resolved
in this order:

| Priority | Source | When Available |
|:--------:|--------|----------------|
| 1 | `JsonWebToken` via CDI | Request thread (inbound token propagation) |
| 2 | `ServiceTokenProvider.getToken()` | Recovery thread (service-to-service) |
| 3 | No token | Neither source available |

This means:

- **Request-thread calls** (close, cancel, join) propagate the caller's token.
- **Recovery-thread calls** (periodic retry) use the service token.
- **If neither is available**, the outbound request has no `Authorization` header.
  The participant decides how to handle unauthenticated requests.

### Participant Trust Model

When a service token is used, the coordinator authenticates to participants with its
own identity (not the original caller's identity). Participants must trust the
coordinator's service account to perform compensations and completions on behalf of
any caller. This is the standard trust model for service-to-service communication in
a microservices architecture.

### Logging

Token resolution is logged at different levels to aid troubleshooting:

| Level | Message | Meaning |
|-------|---------|---------|
| `TRACE` | "JWT token resolved from CDI JsonWebToken" | Token found via CDI injection |
| `TRACE` | "Using CDI JsonWebToken for outbound participant call" | Client filter using injected token |
| `DEBUG` | "CDI JsonWebToken not available: ..." | CDI lookup failed (expected on recovery threads) |
| `DEBUG` | "CDI JsonWebToken not resolvable in client request filter" | Filter has no CDI-injected token |
| `TRACE` | "Bearer token from CDI JsonWebToken configured on REST client builder" | `RestClientConfig` successfully captured token |
| `INFO` | "Service token provider configured: location=..." | `ServiceTokenProvider` initialized |
| `WARN` | "Failed to read service token from ..." | Service token file/HTTP read failed |

## Testing

JWT integration tests are in `test/security/` and run against WildFly via Arquillian
(requires the `-Parq` Maven profile). The tests verify:

- **CDI injection**: `JsonWebToken` is resolvable and returns the raw token when a
  valid Bearer token is provided
- **Token validation**: tokens signed with the correct key are accepted; tokens with
  a wrong issuer are rejected with HTTP 401

Test keys are generated at runtime using `TestKeyManager` -- no PEM files are committed.

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `lra.http-client.providers` | -- | Comma-separated provider classes for outbound HTTP clients |
| `lra.security.service-token.location` | -- | Service token source (file, classpath, or HTTP URL) |
| `lra.security.service-token.refresh-seconds` | `300` | How often to re-read the token from the source |
