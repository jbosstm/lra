/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra;

import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.client.ClientRequestContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Resolves a JWT Bearer token from available sources for outbound HTTP calls.
 *
 * <p>
 * Resolution order (first non-null wins):
 * </p>
 * <ol>
 * <li>Thread-local token captured by {@link PropagateToken @PropagateToken}
 * (only when {@code checkThreadLocal} is {@code true})</li>
 * <li>{@link JsonWebToken} via CDI — available on request threads with an
 * active MicroProfile JWT context</li>
 * <li>Client configuration property {@value LRAConstants#BEARER_TOKEN_PROPERTY}
 * — set at client creation time for async/recovery contexts</li>
 * </ol>
 */
public final class BearerTokenResolver {

    private BearerTokenResolver() {
    }

    /**
     * Resolves a Bearer token from available sources.
     *
     * @param requestContext the outbound request context (for client property fallback)
     * @param checkThreadLocal whether to check {@link Current#getAuthToken()} first
     * @return the raw JWT token string, or {@code null} if none available
     */
    public static String resolve(ClientRequestContext requestContext, boolean checkThreadLocal) {
        String token = null;

        if (checkThreadLocal) {
            token = Current.getAuthToken();
        }

        if (token == null) {
            token = resolveFromCdi();
        }

        if (token == null) {
            Object prop = requestContext.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
            token = prop instanceof String ? (String) prop : null;
        }

        return token;
    }

    /**
     * Attempts to resolve a JWT token from CDI.
     *
     * <p>
     * Returns {@code null} rather than propagating when CDI cannot supply a token, so that
     * {@link #resolve(ClientRequestContext, boolean)} can fall through to the client property.
     * This covers two distinct failures: {@link IllegalStateException} when no CDI container is
     * available at all, and {@link ContextNotActiveException} when a container exists but the
     * request scope that backs {@link JsonWebToken} is not active on the current thread — the
     * latter is the normal case for async LRA start and recovery, which run off the request thread.
     * </p>
     *
     * @return the raw token string, or {@code null} if CDI is unavailable or no token is resolvable
     */
    public static String resolveFromCdi() {
        try {
            Instance<JsonWebToken> jwt = CDI.current().select(JsonWebToken.class);
            if (jwt.isResolvable()) {
                String token = jwt.get().getRawToken();
                if (token != null && LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.trace("JWT token resolved from CDI JsonWebToken");
                }
                return token;
            }
        } catch (ContextNotActiveException e) {
            // No active request scope on this thread (async LRA start, recovery, participant
            // callbacks): the container is present but nothing backs JsonWebToken here. This is
            // expected off the request thread, so trace only; resolution falls through to the
            // client property.
            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef("No active CDI request scope for JWT resolution: %s", e.getMessage());
            }
        } catch (IllegalStateException e) {
            // No CDI container available at all (e.g. a non-CDI runtime).
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("No CDI container available for JWT resolution: %s", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the token has the three dot-separated segments
     * expected of a JWS compact serialization ({@code header.payload.signature}).
     * Does not validate content or signature.
     */
    public static boolean isPlausibleJwt(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        int secondDot = token.indexOf('.', firstDot + 1);
        return secondDot > firstDot + 1 && token.indexOf('.', secondDot + 1) == -1;
    }
}
