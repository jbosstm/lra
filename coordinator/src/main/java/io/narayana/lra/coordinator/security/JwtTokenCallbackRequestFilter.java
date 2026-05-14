/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import io.narayana.lra.BearerTokenResolver;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;

/**
 * Client-side filter that attaches a JWT Bearer token to outbound HTTP requests
 * from the coordinator to participant callbacks (compensate, complete, status, forget, afterLRA).
 *
 * <p>
 * Token resolution is delegated to {@link BearerTokenResolver} without thread-local
 * lookup (the coordinator uses CDI or the client property set by
 * {@link JwtTokenContext#newClient()}).
 * </p>
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
        String token = BearerTokenResolver.resolve(requestContext, false);

        if (token != null && !token.isEmpty()) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }
}
