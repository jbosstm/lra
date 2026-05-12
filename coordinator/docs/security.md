# LRA Security

## Overview

The LRA project supports optional JWT-based security for three concerns:

1. **Inbound authentication** -- validating Bearer tokens on coordinator API endpoints
   (delegated to the container's MicroProfile JWT implementation)
2. **Outbound token propagation** -- forwarding credentials when the coordinator calls
   participant callbacks, or when participants call the coordinator
3. **Recovery thread authentication** -- using a pre-provisioned service token when no
   inbound request context is available

## Inbound Authentication

Inbound JWT authentication is **not implemented by the coordinator itself**. It is the
responsibility of the deployment container:

- **WildFly**: configure via the Elytron subsystem and `microprofile-jwt-smallrye` extension.
  The LRA coordinator subsystem supports a `security-domain` attribute that wires
  BEARER_TOKEN authentication on the internal deployment.
- **Quarkus**: configure via `quarkus.smallrye-jwt.*` properties in `application.properties`

The container validates the JWT, populates `SecurityContext`, and makes `JsonWebToken`
available via CDI.

## Outbound Token Propagation

### Zero-Config: `@PropagateToken`

The simplest way to propagate JWT tokens is the `@PropagateToken` annotation on
participant resource methods. No `lra.http-client.providers` configuration needed:

```java
@LRA(value = LRA.Type.REQUIRED)
@PropagateToken
@GET
public Response startWork() { ... }
```

When a request with a Bearer token hits an `@PropagateToken` method, the LRA runtime:
1. Captures the token from the `Authorization` header
2. Validates it has a plausible JWT structure (three dot-separated segments)
3. Stores it in a thread-local for the duration of the request
4. Automatically registers the token filter on outbound coordinator calls
5. Clears the thread-local after the response

`@PropagateToken` can be placed on individual methods or on the class (applies to all methods).

### Explicit: `lra.http-client.providers`

Two filters are provided for explicit registration:

| Filter | Module | Use case |
|--------|--------|----------|
| `io.narayana.lra.client.JwtTokenClientRequestFilter` | lra-client | Participants calling the coordinator |
| `io.narayana.lra.coordinator.security.JwtTokenCallbackRequestFilter` | lra-coordinator-jar | Coordinator calling participant callbacks |

#### Participant Configuration

```properties
lra.http-client.providers=io.narayana.lra.client.JwtTokenClientRequestFilter
```

#### Coordinator Configuration

```properties
lra.http-client.providers=io.narayana.lra.coordinator.security.JwtTokenCallbackRequestFilter
```

The config key (`lra.http-client.providers`) is shared -- `RestClientConfig` in the
`lra-client` module reads it for the `NarayanaLRAClient`, and `JwtTokenContext` in the
coordinator reads it for direct HTTP calls.

### Token Resolution

Both filters delegate to `BearerTokenResolver` which resolves the token from available
sources in order:

| Priority | Source | When Available | Used by |
|:--------:|--------|----------------|---------|
| 1 | `@PropagateToken` thread-local | `@PropagateToken` on resource method | Client filter only |
| 2 | `JsonWebToken` via CDI | Request thread with MicroProfile JWT | Both filters |
| 3 | Client property `lra.jwt.token` | Set by `JwtTokenContext.newClient()` | Both filters |

The first non-null source wins. The filter adds `Authorization: Bearer <token>` to
the outbound request.

### Structural Validation

When `@PropagateToken` captures a token, the LRA runtime validates it has the
three dot-separated segments expected of a JWS compact serialization
(`header.payload.signature`). Tokens that fail this check are not propagated and
a warning is logged. This catches misconfigured Authorization headers early without
performing full JWT signature or claims validation (that remains the receiver's job).

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
| `http://` / `https://` | `https://token-service/token` | Token vending service (5s timeout, 64KB limit) |

The token is cached for `refresh-seconds` (default 300) and then re-read from the
source. This supports external rotation: a sidecar, init container, or Vault Agent
writes a new token to the file before the old one expires, and the coordinator picks
it up on the next refresh.

### Participant Trust Model

When a service token is used, the coordinator authenticates to participants with its
own identity (not the original caller's identity). Participants must trust the
coordinator's service account to perform compensations and completions on behalf of
any caller.

## Logging

Token resolution is logged at different levels to aid troubleshooting:

| Level | Message | Meaning |
|-------|---------|---------|
| `TRACE` | "JWT token resolved from CDI JsonWebToken" | Token found via CDI lookup |
| `DEBUG` | "CDI not available for JWT resolution: ..." | CDI lookup failed (expected on recovery threads) |
| `WARN` | "@PropagateToken: Authorization header does not contain a valid JWT structure" | Bearer token failed structural validation |
| `INFO` | "Service token provider configured: location=..." | `ServiceTokenProvider` initialized |
| `WARN` | "Failed to read service token from ..." | Service token file/HTTP read failed |

## Testing

JWT integration tests are in `test/security/` and run against WildFly via Arquillian
(requires the `-Parq` Maven profile). The tests verify:

- **CDI injection**: `JsonWebToken` is resolvable and returns the raw token when a
  valid Bearer token is provided
- **Token validation**: tokens signed with the correct key are accepted; tokens with
  a wrong issuer are rejected with HTTP 401

Additional unit tests in `client/` verify:

- **`@PropagateToken` flow**: annotation detection, token capture, structural validation,
  filter propagation, auto-registration via `RestClientConfig`
- **Filter resolution order**: thread-local precedence over CDI and client property
- **Structural JWT validation**: three-segment check accepts valid JWTs, rejects
  malformed, opaque, and multi-segment tokens

Test keys are generated at runtime using `TestKeyManager` -- no PEM files are committed.

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `lra.http-client.providers` | -- | Comma-separated provider classes for outbound HTTP clients |
| `lra.security.service-token.location` | -- | Service token source (file, classpath, or HTTP URL) |
| `lra.security.service-token.refresh-seconds` | `300` | How often to re-read the token from the source |
