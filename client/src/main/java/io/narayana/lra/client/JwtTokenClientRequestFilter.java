/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import io.narayana.lra.BearerTokenResolver;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;

/**
 * Client-side filter that attaches a JWT Bearer token to outbound HTTP requests
 * from LRA participants to the coordinator.
 *
 * <p>
 * Token resolution is delegated to {@link BearerTokenResolver} with thread-local
 * lookup enabled (supports {@link io.narayana.lra.PropagateToken @PropagateToken}).
 * </p>
 *
 * <p>
 * Configure via {@code @PropagateToken} (zero-config) or explicitly:
 * </p>
 *
 * <pre>
 * lra.http-client.providers=io.narayana.lra.client.JwtTokenClientRequestFilter
 * </pre>
 */
public class JwtTokenClientRequestFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        String token = BearerTokenResolver.resolve(requestContext, true);

        if (token != null && !token.isEmpty()) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }
}
