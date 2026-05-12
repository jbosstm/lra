/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Client-side filter that attaches a JWT Bearer token to outbound HTTP requests
 * from the coordinator to participant callbacks (compensate, complete, status, forget, afterLRA).
 *
 * <p>
 * The token is resolved in order:
 * </p>
 * <ol>
 * <li>{@link JsonWebToken} via {@code CDI.current().select()} — available on the
 * request thread when the coordinator received an inbound JWT</li>
 * <li>Client configuration property {@value LRAConstants#BEARER_TOKEN_PROPERTY}, set by
 * {@link JwtTokenContext#newClient()} at client creation time (works across async threads
 * and for recovery thread service tokens)</li>
 * </ol>
 *
 * <p>
 * Register via:
 * </p>
 *
 * <pre>
 * lra.http-client.providers=io.narayana.lra.coordinator.security.JwtTokenCallbackRequestFilter
 * </pre>
 */
public class JwtTokenCallbackRequestFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        String token = resolveFromCdi();

        if (token == null) {
            Object prop = requestContext.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
            token = prop instanceof String ? (String) prop : null;
        }

        if (token != null && !token.isEmpty()) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }

    private String resolveFromCdi() {
        try {
            Instance<JsonWebToken> jwt = CDI.current().select(JsonWebToken.class);
            if (jwt.isResolvable()) {
                String token = jwt.get().getRawToken();
                if (token != null && LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.trace("Using CDI JsonWebToken for outbound participant callback");
                }
                return token;
            }
        } catch (IllegalStateException e) {
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("CDI JsonWebToken not available in callback request filter: %s", e.getMessage());
            }
        }
        return null;
    }
}
