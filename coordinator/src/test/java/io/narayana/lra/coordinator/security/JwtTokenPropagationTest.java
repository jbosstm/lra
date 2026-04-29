/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Tests outbound JWT token propagation via {@link JwtTokenClientRequestFilter}.
 * The filter reads the token from a client configuration property
 * ({@value LRAConstants#BEARER_TOKEN_PROPERTY}) and adds it as a Bearer header.
 */
public class JwtTokenPropagationTest {

    private static UndertowJaxrsServer server;

    @Path("/jwt-test")
    public static class JwtTestResource {

        @GET
        @Path("/echo-auth")
        public Response echoAuth(@HeaderParam(HttpHeaders.AUTHORIZATION) String auth) {
            return Response.ok(auth != null ? auth : "").build();
        }
    }

    @ApplicationPath("/")
    public static class TestApp extends Application {

        @Override
        public Set<Class<?>> getClasses() {
            Set<Class<?>> classes = new HashSet<>();
            classes.add(JwtTestResource.class);
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
    void testClientFilterAddsAuthorizationFromProperty() {
        String echoUrl = TestPortProvider.generateURL("/jwt-test/echo-auth");

        try (Client client = ClientBuilder.newClient()) {
            client.property(LRAConstants.BEARER_TOKEN_PROPERTY, "my-jwt-token");
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(echoUrl).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("Bearer my-jwt-token", response.readEntity(String.class));
            }
        }
    }

    @Test
    void testClientFilterNoTokenNoHeader() {
        String echoUrl = TestPortProvider.generateURL("/jwt-test/echo-auth");

        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(echoUrl).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("", response.readEntity(String.class),
                        "No Authorization header should be sent when no token is available");
            }
        }
    }

    @Test
    void testNewClientCreatesWorkingClient() {
        try (Client client = JwtTokenContext.newClient()) {
            assertNotNull(client);
        }
    }
}
