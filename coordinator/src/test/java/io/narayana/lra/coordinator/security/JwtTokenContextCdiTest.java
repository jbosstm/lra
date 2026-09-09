/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.narayana.lra.LRAConstants;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.ws.rs.client.Client;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link JwtTokenContext#newClient()} against real CDI context semantics using a
 * Weld SE container, with both a callback provider and a service token configured.
 *
 * <p>
 * In the MicroProfile JWT programming model {@link JsonWebToken} is a {@link RequestScoped}
 * bean, so token resolution depends on whether a request scope is active on the calling thread:
 * </p>
 * <ul>
 * <li>request scope active — the inbound caller token is propagated (and takes precedence over
 * the service token);</li>
 * <li>no request scope (recovery, async participant callbacks) — reading the {@code @RequestScoped}
 * {@link JsonWebToken} throws {@link jakarta.enterprise.context.ContextNotActiveException}, which
 * {@code newClient()} must swallow so it falls back to the configured service token instead of
 * aborting the outbound call.</li>
 * </ul>
 *
 * <p>
 * The off-request-thread case is the one that regressed: before the fix
 * {@code getTokenFromCDI()} caught only {@link IllegalStateException}, so on a live container the
 * {@link jakarta.enterprise.context.ContextNotActiveException} escaped {@code newClient()} <em>before</em>
 * the service-token fallback could run — silently breaking recovery-thread authentication. This
 * test reproduces that path with a real container, so it fails without the fix.
 *
 * <p>
 * {@link JwtTokenContext} reads {@code lra.http-client.providers} and
 * {@code lra.security.service-token.location} into {@code static final} fields at class load, so
 * the system properties are set in {@link #setup()} before any test method first references the
 * class. Surefire forks a fresh JVM per test class ({@code reuseForks=false}), so this class-load
 * timing is deterministic and isolated.
 */
public class JwtTokenContextCdiTest {

    private static final String INBOUND_TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJjYWxsZXIifQ.caller-signature";

    private static final String SERVICE_TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJscmEtY29vcmRpbmF0b3IifQ.service-signature";

    private static SeContainer container;

    private static Path serviceTokenFile;

    /**
     * Minimal {@link RequestScoped} {@link JsonWebToken}; only {@link #getRawToken()} carries a
     * value, which is all {@link JwtTokenContext} reads.
     */
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
            return INBOUND_TOKEN;
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        serviceTokenFile = Files.createTempFile("lra-service-token", ".jwt");
        Files.writeString(serviceTokenFile, SERVICE_TOKEN);

        // Set before JwtTokenContext is first referenced so its static-final config is populated.
        System.setProperty(LRAConstants.HTTP_CLIENT_PROVIDERS,
                JwtTokenCallbackRequestFilter.class.getName());
        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, serviceTokenFile.toString());

        container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(RequestScopedJsonWebToken.class)
                .initialize();
    }

    @AfterAll
    static void teardown() throws Exception {
        if (container != null) {
            container.close();
        }
        System.clearProperty(LRAConstants.HTTP_CLIENT_PROVIDERS);
        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
        if (serviceTokenFile != null) {
            Files.deleteIfExists(serviceTokenFile);
        }
    }

    @Test
    void propagatesInboundTokenWhenRequestScopeActive() {
        RequestContextController requestContext = container.select(RequestContextController.class).get();
        requestContext.activate();
        try (Client client = JwtTokenContext.newClient()) {
            Object tokenProperty = client.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
            assertEquals(INBOUND_TOKEN, tokenProperty,
                    "With an active request scope newClient() must propagate the inbound caller token, "
                            + "taking precedence over the configured service token");
        } finally {
            requestContext.deactivate();
        }
    }

    @Test
    void fallsBackToServiceTokenOffRequestThread() throws Exception {
        // A fresh worker thread models the recovery / async callback thread: the CDI container is
        // available JVM-wide, but no request scope is active, so reading the @RequestScoped
        // JsonWebToken throws ContextNotActiveException. newClient() must swallow it and fall back
        // to the service token rather than propagating and aborting the outbound call.
        ExecutorService offRequestThread = Executors.newSingleThreadExecutor();
        try {
            String tokenProperty = offRequestThread.submit(() -> {
                try (Client client = JwtTokenContext.newClient()) {
                    return (String) client.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
                }
            }).get();

            assertEquals(SERVICE_TOKEN, tokenProperty,
                    "Off the request thread newClient() must swallow ContextNotActiveException and "
                            + "fall back to the configured service token");
        } finally {
            offRequestThread.shutdown();
            offRequestThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
