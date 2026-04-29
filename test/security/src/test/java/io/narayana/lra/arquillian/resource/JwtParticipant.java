/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.resource;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;

/**
 * A participant that captures the JWT token it receives from the coordinator
 * during compensate/complete callbacks, making it available for test assertions.
 *
 * <p>
 * Also exposes the CDI-injected {@link JsonWebToken} to verify that the
 * container's MP JWT implementation is active and working.
 */
@ApplicationScoped
@Path(JwtParticipant.ROOT_PATH)
public class JwtParticipant {
    public static final String ROOT_PATH = "jwt-participant";
    public static final String START_LRA_PATH = "start-lra";
    public static final String CDI_TOKEN_PATH = "cdi-token";
    public static final String LAST_CALLBACK_AUTH_PATH = "last-callback-auth";
    @Inject
    Instance<JsonWebToken> jwtTokenInstance;

    private static volatile String lastCallbackAuthHeader;

    @GET
    @Path(START_LRA_PATH)
    @LRA(value = LRA.Type.REQUIRED)
    public Response startLRA(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        return Response.ok(lraId.toASCIIString()).build();
    }

    @GET
    @Path(CDI_TOKEN_PATH)
    public Response getCdiToken() {
        String rawToken = null;
        if (jwtTokenInstance.isResolvable()) {
            rawToken = jwtTokenInstance.get().getRawToken();
        }
        return Response.ok(rawToken != null ? rawToken : "").build();
    }

    @GET
    @Path(LAST_CALLBACK_AUTH_PATH)
    public Response getLastCallbackAuth() {
        return Response.ok(lastCallbackAuthHeader != null ? lastCallbackAuthHeader : "").build();
    }

    @PUT
    @Path("/compensate")
    @Compensate
    public Response compensate(
            @HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {
        lastCallbackAuthHeader = authHeader;
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @PUT
    @Path("/complete")
    @Complete
    public Response complete(
            @HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {
        lastCallbackAuthHeader = authHeader;
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }
}
