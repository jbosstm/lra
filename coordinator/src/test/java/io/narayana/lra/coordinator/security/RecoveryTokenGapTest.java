/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.narayana.lra.LRAConstants;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.ws.rs.client.Client;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the recovery-thread authentication gap with a real CDI container.
 *
 * <p>
 * On a live container the MicroProfile JWT {@link JsonWebToken} is a {@link RequestScoped} bean.
 * The Narayana recovery thread (and async participant callbacks) run outside any request scope, so
 * reading that bean throws {@link jakarta.enterprise.context.ContextNotActiveException}.
 * {@link JwtTokenContext#newClient()} must swallow it and degrade gracefully: when no service token
 * is configured it produces a client with <em>no</em> token property rather than throwing.
 * </p>
 *
 * <p>
 * A callback provider is configured so {@code newClient()} actually attempts CDI resolution (the
 * lookup is skipped when {@code lra.http-client.providers} is empty), and the {@code @RequestScoped}
 * {@link JsonWebToken} is deployed so the failure is specifically {@code ContextNotActiveException}
 * — the exact recovery-thread path. This test therefore fails if {@code getTokenFromCDI()} does not
 * catch {@code ContextNotActiveException}, because the exception would escape {@code newClient()}.
 * </p>
 *
 * <p>
 * {@link ServiceTokenProviderTest#testNewClientUsesServiceTokenWhenNoInboundToken()} verifies that
 * configuring a service token fills this gap; {@link JwtTokenContextCdiTest} covers the same
 * fallback with a live container.
 * </p>
 */
public class RecoveryTokenGapTest {

    private static SeContainer container;

    @RequestScoped
    public static class RequestScopedJsonWebToken implements JsonWebToken {

        @Override
        public String getName() {
            return "caller";
        }

        @Override
        public Set<String> getClaimNames() {
            return Set.of();
        }

        @Override
        public <T> T getClaim(String claimName) {
            return null;
        }

        @Override
        public String getRawToken() {
            return "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJjYWxsZXIifQ.caller-signature";
        }
    }

    @BeforeAll
    static void setup() {
        // Providers configured so newClient() attempts CDI resolution; no service token, so the
        // recovery thread has nothing to fall back to. Set before JwtTokenContext first loads.
        System.setProperty(LRAConstants.HTTP_CLIENT_PROVIDERS,
                JwtTokenCallbackRequestFilter.class.getName());
        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);

        container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(RequestScopedJsonWebToken.class)
                .initialize();
    }

    @AfterAll
    static void teardown() {
        if (container != null) {
            container.close();
        }
        System.clearProperty(LRAConstants.HTTP_CLIENT_PROVIDERS);
    }

    @Test
    void recoveryThreadGetsNoTokenAndDoesNotThrow() throws Exception {
        // A fresh worker thread models the recovery thread: container present, JsonWebToken bean
        // deployed, but no active request scope. newClient() must not throw; with no service token
        // it produces a client with no token property.
        ExecutorService recoveryThread = Executors.newSingleThreadExecutor();
        try {
            String tokenProperty = recoveryThread.submit(() -> {
                try (Client client = JwtTokenContext.newClient()) {
                    return (String) client.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
                }
            }).get();

            assertNull(tokenProperty,
                    "On the recovery thread (no CDI request scope, no service token) newClient() must "
                            + "swallow ContextNotActiveException and produce a client with no token property");
        } finally {
            recoveryThread.shutdown();
            recoveryThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
