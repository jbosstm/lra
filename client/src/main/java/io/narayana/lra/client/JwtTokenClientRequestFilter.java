/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

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
 * from LRA participants to the coordinator.
 *
 * <p>
 * The token is resolved in order:
 * </p>
 * <ol>
 * <li>{@link JsonWebToken} via {@code CDI.current().select()} — available when the
 * participant has an active CDI request context with a validated JWT</li>
 * <li>Client configuration property {@value LRAConstants#BEARER_TOKEN_PROPERTY} — set
 * at client creation time for async/non-CDI contexts</li>
 * </ol>
 *
 * <p>
 * Configure via:
 * </p>
 *
 * <pre>
 * lra.http-client.providers=io.narayana.lra.client.JwtTokenClientRequestFilter
 * </pre>
 */
public class JwtTokenClientRequestFilter implements ClientRequestFilter {

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
                    LRALogger.logger.trace("JWT token resolved from CDI JsonWebToken for outbound LRA client call");
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
}
