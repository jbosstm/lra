/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.narayana.lra.LRAConstants;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.HashSet;
import java.util.Set;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link JwtTokenClientRequestFilter} propagates JWT Bearer tokens
 * on outbound HTTP requests from participants to the coordinator.
 */
public class JwtTokenClientRequestFilterTest {

    private static UndertowJaxrsServer server;

    @Path("/jwt-echo")
    public static class EchoResource {

        @GET
        public Response echoAuth(@HeaderParam(HttpHeaders.AUTHORIZATION) String auth) {
            return Response.ok(auth != null ? auth : "").build();
        }
    }

    @ApplicationPath("/")
    public static class TestApp extends Application {

        @Override
        public Set<Class<?>> getClasses() {
            Set<Class<?>> classes = new HashSet<>();
            classes.add(EchoResource.class);
            return classes;
        }
    }

    @BeforeAll
    static void startServer() {
        server = new UndertowJaxrsServer().start();
        server.deploy(TestApp.class);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void tokenFromPropertyIsPropagated() {
        String url = TestPortProvider.generateURL("/jwt-echo");

        try (Client client = ClientBuilder.newClient()) {
            client.property(LRAConstants.BEARER_TOKEN_PROPERTY, "participant-jwt-token");
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("Bearer participant-jwt-token", response.readEntity(String.class));
            }
        }
    }

    @Test
    void noTokenMeansNoAuthorizationHeader() {
        String url = TestPortProvider.generateURL("/jwt-echo");

        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("", response.readEntity(String.class),
                        "No Authorization header should be sent when no token is available");
            }
        }
    }

    @Test
    void emptyTokenIsNotPropagated() {
        String url = TestPortProvider.generateURL("/jwt-echo");

        try (Client client = ClientBuilder.newClient()) {
            client.property(LRAConstants.BEARER_TOKEN_PROPERTY, "");
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("", response.readEntity(String.class),
                        "Empty token should not produce an Authorization header");
            }
        }
    }
}
