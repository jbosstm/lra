/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Client-side filter that attaches a JWT Bearer token to outbound HTTP requests.
 *
 * <p>
 * The token is resolved in order:
 * <ol>
 * <li>{@link JsonWebToken} via CDI injection (available when the filter is instantiated
 * by a MicroProfile Rest Client on the request thread)</li>
 * <li>Client configuration property {@value LRAConstants#BEARER_TOKEN_PROPERTY}, set by
 * {@link JwtTokenContext#newClient()} at client creation time (works across async threads)</li>
 * </ol>
 *
 * <p>
 * Register this filter via the {@code lra.http-client.providers} MicroProfile Config property:
 *
 * <pre>
 * lra.http-client.providers=io.narayana.lra.coordinator.security.JwtTokenClientRequestFilter
 * </pre>
 */
public class JwtTokenClientRequestFilter implements ClientRequestFilter {

    @Inject
    Instance<JsonWebToken> jwtTokenInstance;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        String token = null;

        if (jwtTokenInstance != null && jwtTokenInstance.isResolvable()) {
            token = jwtTokenInstance.get().getRawToken();
            if (token != null) {
                LRALogger.logger.tracef("Using CDI JsonWebToken for outbound participant call");
            }
        } else if (LRALogger.logger.isDebugEnabled()) {
            LRALogger.logger.debug("CDI JsonWebToken not resolvable in client request filter");
        }

        if (token == null) {
            Object prop = requestContext.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
            token = prop instanceof String ? (String) prop : null;
        }

        if (token != null && !token.isEmpty()) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }
}
