/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.narayana.lra.Current;
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
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@code @PropagateToken} captures the inbound Bearer token in
 * {@link Current#setAuthToken(String)} and that the filter and auto-registration
 * pick it up for outbound calls.
 */
public class PropagateTokenTest {

    private static UndertowJaxrsServer server;

    @Path("/echo")
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

    @AfterEach
    void cleanup() {
        Current.clearAuthToken();
    }

    @Test
    void filterUsesTokenFromCurrent() {
        Current.setAuthToken("propagated-jwt");
        String url = TestPortProvider.generateURL("/echo");

        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("Bearer propagated-jwt", response.readEntity(String.class));
            }
        }
    }

    @Test
    void currentTokenTakesPrecedenceOverClientProperty() {
        Current.setAuthToken("from-annotation");
        String url = TestPortProvider.generateURL("/echo");

        try (Client client = ClientBuilder.newClient()) {
            client.property(LRAConstants.BEARER_TOKEN_PROPERTY, "from-property");
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("Bearer from-annotation", response.readEntity(String.class),
                        "@PropagateToken token should take precedence over client property");
            }
        }
    }

    @Test
    void clearAuthTokenRemovesToken() {
        Current.setAuthToken("temporary");
        Current.clearAuthToken();
        assertNull(Current.getAuthToken());

        String url = TestPortProvider.generateURL("/echo");

        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("", response.readEntity(String.class),
                        "No token should be sent after clearAuthToken()");
            }
        }
    }

    @Test
    void restClientConfigAutoRegistersFilterWhenTokenPresent() {
        Current.setAuthToken("auto-registered");
        String baseUrl = TestPortProvider.generateURL("/");

        EchoClient echoClient = new RestClientConfig()
                .configure(RestClientBuilder.newBuilder().baseUri(java.net.URI.create(baseUrl)))
                .build(EchoClient.class);

        try (Response response = echoClient.echo().toCompletableFuture().join()) {
            assertEquals(200, response.getStatus());
            assertEquals("Bearer auto-registered", response.readEntity(String.class),
                    "RestClientConfig should auto-register filter when Current.getAuthToken() is set");
        }
    }

    @Test
    void restClientConfigDoesNotRegisterFilterWithoutToken() {
        String baseUrl = TestPortProvider.generateURL("/");

        EchoClient echoClient = new RestClientConfig()
                .configure(RestClientBuilder.newBuilder().baseUri(java.net.URI.create(baseUrl)))
                .build(EchoClient.class);

        try (Response response = echoClient.echo().toCompletableFuture().join()) {
            assertEquals(200, response.getStatus());
            assertEquals("", response.readEntity(String.class),
                    "No Authorization header without token or filter configured");
        }
    }

    @Path("/echo")
    public interface EchoClient {

        @GET
        java.util.concurrent.CompletionStage<Response> echo();
    }
}
