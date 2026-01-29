# NarayanaLRAClient - User Guide

## Overview

`NarayanaLRAClient` is a utility class for programmatically controlling the lifecycle of Long Running Actions (LRAs). This client provides a low-level API for interacting with the LRA coordinator.

**WARNING:** While NarayanaLRAClient is available for direct use, the preferred mechanism for LRA management is to use the annotations in the `org.eclipse.microprofile.lra.annotation` package.

**Package:** `io.narayana.lra.client.NarayanaLRAClient`

## Table of Contents

- [Configuration](#configuration)
  - [Coordinator URL Configuration](#coordinator-url-configuration)
  - [HTTP Client Configuration](#http-client-configuration)
  - [Timeout Configuration](#timeout-configuration)
- [Creating a Client Instance](#creating-a-client-instance)
- [Core Methods](#core-methods)
  - [Starting LRAs](#starting-lras)
  - [Ending LRAs](#ending-lras)
  - [Joining LRAs](#joining-lras)
  - [Querying LRA Status](#querying-lra-status)
  - [Getting LRA Information](#getting-lra-information)
  - [Renewing Time Limits](#renewing-time-limits)
  - [Leaving LRAs](#leaving-lras)
  - [Getting All LRAs](#getting-all-lras)
- [Nested LRA Operations](#nested-lra-operations)
- [Advanced Features](#advanced-features)
  - [Load Balancing](#load-balancing)
  - [Context Management](#context-management)
- [Helper Methods](#helper-methods)
- [Usage Examples](#usage-examples)

---

## Configuration

### Coordinator URL Configuration

#### LRA Coordinator URL

**Property:** `lra.coordinator.url`
**Default:** `http://localhost:8080/lra-coordinator`

Specifies the URL(s) of the LRA coordinator. For multiple coordinators (clustering), provide a comma-separated list:

```properties
lra.coordinator.url=http://host1:8080/lra-coordinator,http://host2:8080/lra-coordinator
```

#### Load Balancing Method

**Property:** `lra.coordinator.lb-method`
**Default:** `round-robin`

Specifies the load balancing algorithm when multiple coordinators are configured. See [Load Balancing](#load-balancing) for details.

Supported values:
- `round-robin` (default, supports failover)
- `sticky`
- `random`
- `least-requests`
- `least-response-time`
- `power-of-two-choices`

```properties
lra.coordinator.lb-method=round-robin
```

---

### HTTP Client Configuration

The client uses MicroProfile REST Client with support for SSL/TLS, timeouts, and custom providers. All HTTP client configuration properties use the prefix `lra.http-client.*`.

#### SSL/TLS Configuration

**Trust Store Configuration** (for server certificate validation):

```properties
# Path to truststore (supports file://, classpath://, or filesystem path)
lra.http-client.trustStore=file:///path/to/truststore.jks
# or
lra.http-client.trustStore=classpath://truststore.jks

# Truststore password
lra.http-client.trustStorePassword=changeit

# Truststore type (JKS or PKCS12)
lra.http-client.trustStoreType=JKS
```

**Key Store Configuration** (for mutual TLS/client certificates):

```properties
# Path to keystore
lra.http-client.keyStore=file:///path/to/keystore.jks

# Keystore password
lra.http-client.keyStorePassword=changeit

# Keystore type
lra.http-client.keyStoreType=JKS
```

**Custom Hostname Verifier:**

```properties
# Fully qualified class name of custom HostnameVerifier
lra.http-client.hostnameVerifier=com.example.MyHostnameVerifier
```

#### HTTP Timeout Configuration

```properties
# Connection timeout in milliseconds
lra.http-client.connectTimeout=5000

# Read timeout in milliseconds
lra.http-client.readTimeout=30000
```

#### Custom Providers

```properties
# Comma-separated list of JAX-RS provider class names to register
lra.http-client.providers=com.example.MyProvider1,com.example.MyProvider2
```

**Complete SSL Example:**

```properties
# HTTPS coordinator with mutual TLS
lra.coordinator.url=https://secure-coordinator:8443/lra-coordinator
lra.http-client.trustStore=file:///etc/ssl/truststore.p12
lra.http-client.trustStorePassword=trustpass
lra.http-client.trustStoreType=PKCS12
lra.http-client.keyStore=file:///etc/ssl/client-keystore.p12
lra.http-client.keyStorePassword=keypass
lra.http-client.keyStoreType=PKCS12
lra.http-client.connectTimeout=10000
lra.http-client.readTimeout=60000
```

---

### Timeout Configuration

The client supports configurable timeouts for various LRA operations (in seconds):

| System Property | Default | Description |
|----------------|---------|-------------|
| `lra.internal.client.timeout` | 10 | Default timeout for all operations |
| `lra.internal.client.timeout.start` | 10 | Timeout for starting LRAs |
| `lra.internal.client.timeout.join` | 10 | Timeout for joining LRAs |
| `lra.internal.client.end.timeout` | 10 | Timeout for closing/canceling LRAs |
| `lra.internal.client.leave.timeout` | 10 | Timeout for leaving LRAs |
| `lra.internal.client.query.timeout` | 10 | Timeout for status queries |

Example:
```bash
-Dlra.internal.client.timeout.start=30 -Dlra.internal.client.end.timeout=60
```

---

## Creating a Client Instance

### Default Constructor

Uses the coordinator URL from the `lra.coordinator.url` property:

```java
NarayanaLRAClient client = new NarayanaLRAClient();
```

### With Specific Coordinator URL (String)

```java
NarayanaLRAClient client = new NarayanaLRAClient("http://coordinator:8080/lra-coordinator");
```

### With Specific Coordinator URL (URI)

```java
URI coordinatorUrl = new URI("http://coordinator:8080/lra-coordinator");
NarayanaLRAClient client = new NarayanaLRAClient(coordinatorUrl);
```

### Deprecated Constructor (For Removal)

```java
@Deprecated(since = "1.0.3.Final", forRemoval = true)
NarayanaLRAClient client = new NarayanaLRAClient("http", "localhost", 8080, "/lra-coordinator");
```

### Resource Management

The client implements `Closeable` and should be closed when done:

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    // Use client
} // Automatically closed
```

Or manually:

```java
NarayanaLRAClient client = new NarayanaLRAClient();
try {
    // Use client
} finally {
    client.close();
}
```

**Important:** Calling `close()` is especially important when using load balancing, as it shuts down the Stork service discovery resources.

---

## Core Methods

### Starting LRAs

#### `startLRA(String clientID)`

Starts a new top-level LRA with no timeout.

**Parameters:**
- `clientID` - Client identifier for the LRA (can be null or empty)

**Returns:** URI of the created LRA

**Throws:** `WebApplicationException` if the operation fails

```java
URI lraId = client.startLRA("my-client-id");
```

#### `startLRA(URI parentLRA, String clientID, Long timeout, ChronoUnit unit)`

Starts a new LRA with full configuration options.

**Parameters:**
- `parentLRA` - Parent LRA URI for nested LRAs (null for top-level)
- `clientID` - Client identifier (can be null)
- `timeout` - Timeout value (0 or null for no timeout)
- `unit` - Time unit for timeout (null defaults to SECONDS)

**Returns:** URI of the created LRA

**Throws:** `WebApplicationException` if the operation fails

```java
// Top-level LRA with 10-minute timeout
URI lraId = client.startLRA(null, "client-1", 10L, ChronoUnit.MINUTES);

// Nested LRA
URI childLra = client.startLRA(parentLraId, "child-1", 5L, ChronoUnit.MINUTES);
```

**Behavior:**
- When a timeout is specified, the LRA will automatically compensate when the timeout expires
- If load balancing is enabled, the coordinator is selected using the configured algorithm
- The LRA context is automatically pushed onto the thread-local context stack
- Returns HTTP 201 (Created) on success with LRA ID in Location header

---

### Ending LRAs

#### `closeLRA(URI lraId)`

Closes (completes) an LRA, triggering all participant complete callbacks.

**Parameters:**
- `lraId` - URI of the LRA to close

**Throws:** `WebApplicationException` if the operation fails

```java
client.closeLRA(lraId);
```

#### `closeLRA(URI lraId, String compensator, String userData)`

Closes an LRA with additional participant data.

**Parameters:**
- `lraId` - URI of the LRA to close
- `compensator` - Compensator link header
- `userData` - User data to pass to participants

```java
client.closeLRA(lraId, compensatorLink, "additional-data");
```

#### `cancelLRA(URI lraId)`

Cancels (compensates) an LRA, triggering all participant compensate callbacks.

**Parameters:**
- `lraId` - URI of the LRA to cancel

**Throws:** `WebApplicationException` if the operation fails

```java
client.cancelLRA(lraId);
```

#### `cancelLRA(URI lraId, String compensator, String userData)`

Cancels an LRA with additional participant data.

```java
client.cancelLRA(lraId, compensatorLink, "rollback-reason");
```

**Expected Status Codes:**
- 200 OK - LRA ended successfully
- 202 ACCEPTED - End request accepted, processing asynchronously
- 404 NOT_FOUND - LRA not found (already completed/cancelled)

**Behavior:**
- After ending, the LRA is automatically popped from the thread-local context stack
- The operation is asynchronous - status 202 means completion is in progress

---

### Joining LRAs

#### `joinLRA(URI lraId, Long timeLimit, URI compensateUri, URI completeUri, URI forgetUri, URI leaveUri, URI afterUri, URI statusUri, String compensatorData)`

Enlists a participant in an LRA with individual callback URIs.

**Parameters:**
- `lraId` - URI of the LRA to join
- `timeLimit` - Participant timeout in milliseconds (0 for no limit)
- `compensateUri` - URI for compensation callback
- `completeUri` - URI for completion callback
- `forgetUri` - URI for forget callback (can be null)
- `leaveUri` - URI for leave callback (can be null)
- `afterUri` - URI for after-LRA callback (can be null)
- `statusUri` - URI for status callback (can be null)
- `compensatorData` - Data to pass to participant callbacks

**Returns:** Recovery URI for this participant enrollment

**Throws:**
- `WebApplicationException` with status 412 (PRECONDITION_FAILED) if too late to join
- `WebApplicationException` with status 410 (GONE) if LRA not found

```java
URI recoveryUrl = client.joinLRA(
    lraId,
    30000L, // 30 second timeout
    URI.create("http://myservice/compensate"),
    URI.create("http://myservice/complete"),
    null, // no forget
    null, // no leave
    null, // no after
    URI.create("http://myservice/status"),
    "my-participant-data"
);
```

#### `joinLRA(URI lraId, Long timeLimit, URI compensateUri, URI completeUri, URI forgetUri, URI leaveUri, URI afterUri, URI statusUri, StringBuilder compensatorData)`

Same as above but with mutable `StringBuilder` for compensator data. The buffer will be updated with previous participant data if re-joining.

#### `joinLRA(URI lraId, Long timeLimit, URI participantUri, StringBuilder compensatorData)`

Enlists a participant using a single participant URI.

**Parameters:**
- `lraId` - URI of the LRA to join
- `timeLimit` - Participant timeout in milliseconds
- `participantUri` - Base URI of the participant (callbacks discovered via annotations)
- `compensatorData` - Mutable data buffer (updated with previous data if re-joining)

**Returns:** Recovery URI for this participant enrollment

```java
StringBuilder data = new StringBuilder("initial-data");
URI recoveryUrl = client.joinLRA(
    lraId,
    60000L,
    URI.create("http://myservice/participant"),
    data
);
// data may now contain previous participant data if re-joining
```

**Important Notes:**
- The recovery URL can be used to query or manage this specific participant enrollment
- If `compensatorData` is a `StringBuilder`, it will be updated with any previous participant data from the coordinator

---

### Querying LRA Status

#### `getStatus(URI lraId)`

Queries the current status of an LRA.

**Parameters:**
- `lraId` - URI of the LRA

**Returns:** `LRAStatus` enum value, or null if LRA not found

**Throws:**
- `NotFoundException` if LRA doesn't exist (status 404)
- `WebApplicationException` for other errors

**Possible Status Values:**
- `Active` - LRA is running
- `Closing` - LRA is completing participants
- `Closed` - LRA completed successfully
- `Cancelling` - LRA is compensating participants
- `Cancelled` - LRA was compensated
- `FailedToClose` - Completion failed
- `FailedToCancel` - Compensation failed

```java
try {
    LRAStatus status = client.getStatus(lraId);
    if (status == null) {
        // LRA not found or already removed
    } else if (status == LRAStatus.Active) {
        // LRA is still running
    }
} catch (NotFoundException e) {
    // LRA definitely doesn't exist
}
```

---

### Getting LRA Information

#### `getLRAInfo(URI lraId)`

Gets detailed information about a specific LRA.

**Parameters:**
- `lraId` - URI of the LRA

**Returns:** `LRAData` object containing detailed information

**Throws:** `WebApplicationException` if the request fails

```java
LRAData info = client.getLRAInfo(lraId);
System.out.println("LRA ID: " + info.getLraId());
System.out.println("Status: " + info.getStatus());
System.out.println("Client ID: " + info.getClientId());
System.out.println("Start Time: " + info.getStartTime());
System.out.println("Finish Time: " + info.getFinishTime());
System.out.println("Participants: " + info.getEnlistedParticipants().size());
```

#### `getLRAInfo(URI lraId, String acceptMediaType)`

Gets detailed LRA information with a specific media type preference.

**Parameters:**
- `lraId` - URI of the LRA
- `acceptMediaType` - Response content type (e.g., `MediaType.APPLICATION_JSON`)

**Returns:** `LRAData` object

```java
LRAData info = client.getLRAInfo(lraId, MediaType.APPLICATION_JSON);
```

---

### Renewing Time Limits

#### `renewTimeLimit(URI lraId, Long timeLimit)`

Updates the timeout for an existing LRA.

**Parameters:**
- `lraId` - URI of the LRA
- `timeLimit` - New time limit in milliseconds (0 for no limit)

**Throws:** `WebApplicationException` if the request fails

```java
// Extend LRA timeout to 5 minutes from now
client.renewTimeLimit(lraId, 300000L);

// Remove timeout
client.renewTimeLimit(lraId, 0L);
```

**Use Case:** Useful when long-running work takes more time than initially estimated.

---

### Leaving LRAs

#### `leaveLRA(URI lraId, String body)`

Removes a participant from an active LRA.

**Parameters:**
- `lraId` - URI of the LRA to leave
- `body` - Optional body content (can be null)

**Throws:** `WebApplicationException` if the operation fails

```java
client.leaveLRA(lraId, null);
// or with data
client.leaveLRA(lraId, "leaving-reason");
```

**Expected Behavior:**
- Only works on active LRAs
- Participant will not be notified when LRA completes/compensates
- Returns 200 OK on success

---

### Getting All LRAs

#### `getAllLRAs()`

Retrieves a list of all LRAs known to the coordinator.

**Returns:** List of `LRAData` objects

**Throws:** `WebApplicationException` on timeout or error

```java
List<LRAData> allLras = client.getAllLRAs();
for (LRAData lra : allLras) {
    System.out.println("LRA: " + lra.getLraId());
    System.out.println("Status: " + lra.getStatus());
    System.out.println("Client ID: " + lra.getClientId());
    System.out.println("---");
}
```

---

## Nested LRA Operations

Nested LRAs are child LRAs that participate in a parent LRA. The client provides special operations for managing nested LRAs.

### `getNestedLRAStatus(URI nestedLraId)`

Gets the participant status of a nested LRA (how it appears to its parent).

**Parameters:**
- `nestedLraId` - URI of the nested LRA

**Returns:** `ParticipantStatus` enum value

**Throws:** `WebApplicationException` if the request fails

**Possible ParticipantStatus Values:**
- `Active`
- `Compensating`
- `Compensated`
- `FailedToCompensate`
- `Completing`
- `Completed`
- `FailedToComplete`

```java
ParticipantStatus status = client.getNestedLRAStatus(nestedLraId);
if (status == ParticipantStatus.Completed) {
    System.out.println("Nested LRA completed successfully");
}
```

### `completeNestedLRA(URI nestedLraId)`

Triggers completion of a nested LRA as if its parent LRA is completing.

**Parameters:**
- `nestedLraId` - URI of the nested LRA

**Returns:** Final `ParticipantStatus` of the nested LRA

**Throws:** `WebApplicationException` if the request fails

```java
ParticipantStatus finalStatus = client.completeNestedLRA(nestedLraId);
```

### `compensateNestedLRA(URI nestedLraId)`

Triggers compensation of a nested LRA as if its parent LRA is compensating.

**Parameters:**
- `nestedLraId` - URI of the nested LRA

**Returns:** Final `ParticipantStatus` of the nested LRA

**Throws:** `WebApplicationException` if the request fails

```java
ParticipantStatus finalStatus = client.compensateNestedLRA(nestedLraId);
```

### `forgetNestedLRA(URI nestedLraId)`

Removes a nested LRA from the coordinator's memory after it has completed or compensated.

**Parameters:**
- `nestedLraId` - URI of the nested LRA

**Throws:** `WebApplicationException` if the request fails

```java
client.forgetNestedLRA(nestedLraId);
```

---

## Advanced Features

### Load Balancing

When multiple coordinator URLs are configured, the client can load balance LRA creation across them.

#### Supported Algorithms

| Algorithm | Constant | Description | Failover Support |
|-----------|----------|-------------|------------------|
| Round Robin | `LB_METHOD_ROUND_ROBIN` | Distributes requests evenly (default) | Yes |
| Sticky | `LB_METHOD_STICKY` | Pins to same coordinator per client | No |
| Random | `LB_METHOD_RANDOM` | Random selection | No |
| Least Requests | `LB_METHOD_LEAST_REQUESTS` | Coordinator with fewest requests | No |
| Least Response Time | `LB_METHOD_LEAST_RESPONSE_TIME` | Fastest coordinator | No |
| Power of Two Choices | `LB_METHOD_POWER_OF_TWO_CHOICES` | Randomly picks best of two | No |

#### Configuration Example

```properties
lra.coordinator.url=http://coord1:8080/lra-coordinator,http://coord2:8080/lra-coordinator,http://coord3:8080/lra-coordinator
lra.coordinator.lb-method=least-response-time
```

#### Checking Load Balancing Status

```java
if (client.isLoadBalancing()) {
    System.out.println("Client is using load balancing");
}
```

#### Failover Support

- Only `round-robin` load balancing supports automatic failover
- If a coordinator is unavailable, the next one in the list is tried
- Other algorithms will fail immediately on coordinator unavailability

#### Dependencies

Load balancing requires Smallrye Stork dependencies on the classpath:

```xml
<dependency>
    <groupId>io.smallrye.stork</groupId>
    <artifactId>stork-core</artifactId>
</dependency>
<dependency>
    <groupId>io.smallrye.stork</groupId>
    <artifactId>stork-service-discovery-static-list</artifactId>
</dependency>
```

If missing, the client falls back to using the first coordinator in the list.

---

### Context Management

The client maintains a thread-local stack of active LRAs.

#### `getCurrent()`

Gets the current (top-most) LRA from the context stack.

**Returns:** URI of current LRA, or null if none

```java
URI currentLra = client.getCurrent();
```

#### `clearCurrent(boolean all)`

Clears LRA context from the thread-local stack.

**Parameters:**
- `all` - If true, clears entire stack; if false, pops only top LRA

```java
client.clearCurrent(false); // Pop one LRA
client.clearCurrent(true);  // Clear entire stack
```

#### `setCurrentLRA(URI lraId)`

Changes the client's coordinator URL based on the LRA ID.

**Parameters:**
- `lraId` - LRA URI containing coordinator URL

**Use Case:** When working with an LRA created on a different coordinator instance.

```java
client.setCurrentLRA(lraId);
// Client now uses the coordinator from lraId
```

**Behavior:**
- Extracts coordinator URL from LRA ID
- Updates client's internal coordinator URL
- Useful in clustered environments

---

## Helper Methods

### `getCoordinatorUrl()`

Returns the current coordinator URL as a string.

```java
String coordUrl = client.getCoordinatorUrl();
```

### `getRecoveryUrl()`

Returns the recovery coordinator URL.

```java
String recoveryUrl = client.getRecoveryUrl();
// Returns: <coordinatorUrl>/recovery
```

### `getTerminationUris(Class<?> compensatorClass, String uriPrefix, Long timeout)`

Static helper that extracts termination URIs from a compensator class using reflection.

**Parameters:**
- `compensatorClass` - Class with LRA annotations
- `uriPrefix` - Base URI for the participant
- `timeout` - Participant timeout

**Returns:** Map of callback types to URIs

```java
Map<String, String> uris = NarayanaLRAClient.getTerminationUris(
    MyCompensator.class,
    "http://myservice",
    30000L
);
// Returns: {compensate: "http://myservice/compensate?method=...",
//           complete: "http://myservice/complete?method=...", ...}
```

**Use Case:** Programmatically discovering participant callbacks from annotated classes.

### `isAsyncCompletion(Method method)`

Static helper to check if a method is configured for asynchronous completion.

**Parameters:**
- `method` - Method to check

**Returns:** true if method uses `@Suspended` annotation with `@Complete` or `@Compensate`

```java
Method method = MyClass.class.getMethod("compensate", ...);
if (NarayanaLRAClient.isAsyncCompletion(method)) {
    System.out.println("This compensator is asynchronous");
}
```

---

## Usage Examples

### Example 1: Simple LRA Lifecycle

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    // Start an LRA
    URI lraId = client.startLRA("order-service");
    System.out.println("Started LRA: " + lraId);

    // Do some work...
    processOrder();

    // Check status
    LRAStatus status = client.getStatus(lraId);
    if (status == LRAStatus.Active) {
        // Complete the LRA
        client.closeLRA(lraId);
        System.out.println("LRA closed successfully");
    }
} catch (WebApplicationException e) {
    System.err.println("LRA operation failed: " + e.getMessage());
}
```

### Example 2: LRA with Timeout and Participant

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    // Start LRA with 5-minute timeout
    URI lraId = client.startLRA(null, "payment-service", 5L, ChronoUnit.MINUTES);

    // Join as participant
    URI recoveryUrl = client.joinLRA(
        lraId,
        300000L, // 5 minutes in milliseconds
        URI.create("http://payment-service/compensate"),
        URI.create("http://payment-service/complete"),
        null,
        null,
        null,
        URI.create("http://payment-service/status"),
        "{\"orderId\": \"12345\"}"
    );

    System.out.println("Joined LRA, recovery URL: " + recoveryUrl);

    // Process payment...
    boolean paymentSuccess = processPayment();

    if (paymentSuccess) {
        client.closeLRA(lraId);
    } else {
        client.cancelLRA(lraId);
    }
}
```

### Example 3: Nested LRAs

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    // Start parent LRA
    URI parentLra = client.startLRA("order-workflow");

    // Start nested LRA
    URI childLra = client.startLRA(
        parentLra,           // parent
        "inventory-check",   // client ID
        60L,                 // 1 minute timeout
        ChronoUnit.SECONDS
    );

    // Work with child LRA
    boolean inventoryAvailable = checkInventory();

    if (inventoryAvailable) {
        client.closeLRA(childLra);

        // Verify nested LRA status
        ParticipantStatus nestedStatus = client.getNestedLRAStatus(childLra);
        System.out.println("Nested LRA status: " + nestedStatus);
    } else {
        client.cancelLRA(childLra);
    }

    // Continue with parent
    client.closeLRA(parentLra);
}
```

### Example 4: Load Balanced Setup with HTTPS

Configuration:
```properties
# Multiple coordinators with SSL
lra.coordinator.url=https://coord1:8443/lra-coordinator,https://coord2:8443/lra-coordinator
lra.coordinator.lb-method=round-robin

# SSL configuration
lra.http-client.trustStore=file:///etc/ssl/truststore.jks
lra.http-client.trustStorePassword=changeit
lra.http-client.trustStoreType=JKS
lra.http-client.connectTimeout=10000
lra.http-client.readTimeout=30000
```

Code:
```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    if (client.isLoadBalancing()) {
        System.out.println("Using load balancing across multiple coordinators");
    }

    // Create multiple LRAs - will be distributed across coordinators
    for (int i = 0; i < 10; i++) {
        URI lra = client.startLRA("client-" + i);
        System.out.println("Created LRA " + i + ": " + lra);
        // Each LRA may be on a different coordinator
    }
}
```

### Example 5: Error Handling

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    URI lraId = client.startLRA("my-service");

    try {
        // Attempt to join
        URI recovery = client.joinLRA(
            lraId, 30000L,
            URI.create("http://myservice/participant"),
            new StringBuilder()
        );

        // Do work...

    } catch (WebApplicationException e) {
        Response response = e.getResponse();

        if (response.getStatus() == 412) {
            System.err.println("Too late to join LRA - it may be closing/cancelling");
        } else if (response.getStatus() == 410) {
            System.err.println("LRA no longer exists");
        } else {
            System.err.println("Failed to join: " + response.readEntity(String.class));
        }

        // Compensate since we couldn't join properly
        client.cancelLRA(lraId);
    }
}
```

### Example 6: Monitoring and Managing LRAs

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    // Get all LRAs
    List<LRAData> allLras = client.getAllLRAs();

    for (LRAData lra : allLras) {
        System.out.println("=== LRA " + lra.getLraId() + " ===");
        System.out.println("Status: " + lra.getStatus());
        System.out.println("Client ID: " + lra.getClientId());

        // Get detailed information
        LRAData detailedInfo = client.getLRAInfo(lra.getLraId());
        System.out.println("Participants: " + detailedInfo.getEnlistedParticipants().size());
        System.out.println("Start Time: " + detailedInfo.getStartTime());

        // Check if stuck in Active state for too long
        if (lra.getStatus() == LRAStatus.Active) {
            LRAStatus currentStatus = client.getStatus(lra.getLraId());
            System.out.println("Current status: " + currentStatus);

            // Potentially extend timeout if needed
            if (shouldExtend(lra)) {
                client.renewTimeLimit(lra.getLraId(), 600000L); // 10 more minutes
                System.out.println("Extended timeout");
            }
        }
    }
}
```

### Example 7: Nested LRA Management

```java
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    // Start parent
    URI parentLra = client.startLRA("parent-workflow");

    // Start multiple nested LRAs
    List<URI> childLras = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
        URI child = client.startLRA(parentLra, "sub-task-" + i, 2L, ChronoUnit.MINUTES);
        childLras.add(child);
    }

    // Process each child
    for (URI childLra : childLras) {
        try {
            processSubTask(childLra);

            // Complete the nested LRA directly
            ParticipantStatus status = client.completeNestedLRA(childLra);
            System.out.println("Nested LRA completed with status: " + status);

            // Clean up
            client.forgetNestedLRA(childLra);

        } catch (Exception e) {
            // Compensate this specific nested LRA
            ParticipantStatus status = client.compensateNestedLRA(childLra);
            System.out.println("Nested LRA compensated with status: " + status);
        }
    }

    // Close parent
    client.closeLRA(parentLra);
}
```

### Example 8: Dynamic Participant Registration

```java
public class PaymentService {

    @Compensate
    @PUT
    @Path("/compensate")
    public void compensate() {
        // Refund payment
    }

    @Complete
    @PUT
    @Path("/complete")
    public void complete() {
        // Finalize payment
    }
}

// Use the client to discover endpoints
try (NarayanaLRAClient client = new NarayanaLRAClient()) {
    URI lraId = client.startLRA("payment-flow");

    // Discover termination URIs from class
    Map<String, String> uris = NarayanaLRAClient.getTerminationUris(
        PaymentService.class,
        "http://payment-service",
        30000L
    );

    System.out.println("Discovered URIs: " + uris);

    // Join using discovered URIs
    URI recoveryUrl = client.joinLRA(
        lraId,
        30000L,
        URI.create(uris.get("compensate")),
        URI.create(uris.get("complete")),
        null, null, null, null,
        "payment-data"
    );

    // Continue with workflow...
    client.closeLRA(lraId);
}
```

---

## Important Notes

1. **Resource Management**: Always call `close()` when done, especially when using load balancing (to shutdown Stork resources).

2. **Thread Safety**: The client uses thread-local context management. Each thread maintains its own LRA context stack.

3. **Asynchronous Operations**: All coordinator communication is asynchronous using `CompletionStage`. Operations have configurable timeouts.

4. **HTTP Client Configuration**: The client uses MicroProfile REST Client which can be configured via `lra.http-client.*` properties for SSL/TLS, timeouts, and custom providers.

5. **Error Handling**: Operations throw `WebApplicationException` with status codes and messages from the coordinator. Always check status codes for specific error conditions.

6. **API Versioning**: The client sends `Narayana-LRA-API-version: 1.2` header. Ensure your coordinator supports this version.

7. **Context Propagation**: When starting an LRA, the client automatically pushes it to the thread-local context and pops it when ending.

8. **Load Balancing Requirements**: Load balancing requires Smallrye Stork dependencies. Without them, the client falls back to the first coordinator.

9. **Nested LRAs**: Nested LRAs are treated as participants in their parent LRA. They have special management methods for status checking and lifecycle control.

10. **Timeout Behavior**: When an LRA timeout expires, the coordinator automatically triggers compensation of all participants.

---

## Further Reading

- [MicroProfile LRA Specification](https://github.com/eclipse/microprofile-lra)
- [Narayana LRA Documentation](https://narayana.io/)
- LRA API Documentation: See `API.adoc` in the project root
- For annotation-based usage, see `org.eclipse.microprofile.lra.annotation` package
- [Smallrye Stork Documentation](https://smallrye.io/smallrye-stork/) for load balancing details

---

