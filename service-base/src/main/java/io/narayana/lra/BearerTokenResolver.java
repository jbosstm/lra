/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra;

import io.narayana.lra.logging.LRALogger;
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
        } catch (IllegalStateException e) {
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("CDI not available for JWT resolution: %s", e.getMessage());
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
