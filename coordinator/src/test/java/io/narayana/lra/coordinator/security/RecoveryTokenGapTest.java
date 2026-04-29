/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.narayana.lra.LRAConstants;
import jakarta.ws.rs.client.Client;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates that the recovery thread has no CDI request scope,
 * so {@code CDI.current().select(JsonWebToken.class)} fails and
 * {@link JwtTokenContext#newClient()} produces a client with no token property
 * (unless a {@link ServiceTokenProvider} is configured).
 *
 * <p>
 * The {@link ServiceTokenProviderTest} verifies that configuring a service
 * token fills this gap.
 */
public class RecoveryTokenGapTest {

    @Test
    void testRecoveryThreadHasNoTokenInContext() throws Exception {
        Thread recoveryThread = new Thread(() -> {
            try (Client client = JwtTokenContext.newClient()) {
                Object tokenProperty = client.getConfiguration()
                        .getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
                assertNull(tokenProperty,
                        "Client created on recovery thread (no CDI scope, no service token) "
                                + "should have no token property");
            }
        });

        recoveryThread.start();
        recoveryThread.join();
    }
}
