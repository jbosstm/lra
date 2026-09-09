/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.narayana.lra.BearerTokenResolver;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BearerTokenResolver#resolveFromCdi()} against real CDI context
 * semantics using a Weld SE container.
 *
 * <p>
 * In the MicroProfile JWT programming model {@link JsonWebToken} is a
 * {@link RequestScoped} bean, so resolution depends on whether a request scope is
 * active on the current thread:
 * </p>
 * <ul>
 * <li>request scope active — the raw token is resolved;</li>
 * <li>no request scope (async LRA start, recovery, participant callbacks) — CDI throws
 * {@link jakarta.enterprise.context.ContextNotActiveException}, which the resolver must
 * swallow so callers fall through to the client property rather than aborting the call;</li>
 * <li>no CDI container at all — {@link IllegalStateException}, likewise swallowed.</li>
 * </ul>
 */
public class BearerTokenResolverCdiTest {

    private static final String RAW_TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature";

    /**
     * Minimal {@link RequestScoped} {@link JsonWebToken}; only {@link #getRawToken()}
     * carries a value, which is all {@link BearerTokenResolver} reads.
     */
    @RequestScoped
    public static class RequestScopedJsonWebToken implements JsonWebToken {

        @Override
        public String getName() {
            return "test-subject";
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
            return RAW_TOKEN;
        }
    }

    private static SeContainer startContainer() {
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(RequestScopedJsonWebToken.class)
                .initialize();
    }

    @Test
    void resolvesRawTokenWhenRequestScopeActive() {
        try (SeContainer container = startContainer()) {
            RequestContextController requestContext = container.select(RequestContextController.class).get();
            requestContext.activate();
            try {
                assertEquals(RAW_TOKEN, BearerTokenResolver.resolveFromCdi(),
                        "With an active request scope the raw JWT must be resolved from the CDI JsonWebToken");
            } finally {
                requestContext.deactivate();
            }
        }
    }

    @Test
    void returnsNullOffTheRequestThread() throws Exception {
        try (SeContainer container = startContainer()) {
            // A fresh worker thread models an async LRA / recovery / callback thread: the CDI
            // container is available JVM-wide, but no request scope is active, so reading the
            // @RequestScoped JsonWebToken throws ContextNotActiveException. resolveFromCdi must
            // swallow it and return null instead of propagating and aborting the outbound call.
            ExecutorService offRequestThread = Executors.newSingleThreadExecutor();
            try {
                String token = offRequestThread.submit(BearerTokenResolver::resolveFromCdi).get();
                assertNull(token,
                        "Off the request thread resolveFromCdi must swallow ContextNotActiveException and return null");
            } finally {
                offRequestThread.shutdown();
                offRequestThread.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void returnsNullWhenNoCdiContainer() {
        // No SeContainer is running here, so CDI.current() throws IllegalStateException,
        // which resolveFromCdi must also swallow.
        assertNull(BearerTokenResolver.resolveFromCdi(),
                "With no CDI container resolveFromCdi must swallow IllegalStateException and return null");
    }
}
