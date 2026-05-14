/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.AnnotationResolver;
import io.narayana.lra.BearerTokenResolver;
import io.narayana.lra.Current;
import io.narayana.lra.PropagateToken;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration-style test that verifies the full {@code @PropagateToken} flow:
 * annotation detection (same logic as {@code ServerLRAFilter}), structural JWT
 * validation, token capture into {@link Current}, and propagation via
 * {@link JwtTokenClientRequestFilter} to an outbound HTTP call.
 */
public class PropagateTokenIntegrationTest {

    private static final String VALID_JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature";
    private static UndertowJaxrsServer server;

    // --- Real annotated resource classes ---

    @Path("/participant")
    @PropagateToken
    public static class ClassLevelAnnotatedParticipant {

        @GET
        @Path("/action")
        public Response action() {
            return Response.ok().build();
        }
    }

    @Path("/participant2")
    public static class MethodLevelAnnotatedParticipant {

        @PropagateToken
        @GET
        @Path("/action")
        public Response action() {
            return Response.ok().build();
        }

        @GET
        @Path("/no-propagate")
        public Response noPropagate() {
            return Response.ok().build();
        }
    }

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

    /**
     * Simulates exactly what ServerLRAFilter does: detect annotation, extract
     * Bearer token, validate JWT structure, store in Current.
     */
    private static void simulateServerLRAFilter(Method method, String authHeader) {
        if (AnnotationResolver.isAnnotationPresent(PropagateToken.class, method)
                || method.getDeclaringClass().isAnnotationPresent(PropagateToken.class)) {
            if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = authHeader.substring(7);
                if (BearerTokenResolver.isPlausibleJwt(token)) {
                    Current.setAuthToken(token);
                }
            }
        }
    }

    // --- Annotation detection tests ---

    @Test
    void annotationDetectedOnMethod() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        assertTrue(AnnotationResolver.isAnnotationPresent(PropagateToken.class, method));
    }

    @Test
    void annotationNotDetectedOnUnannotatedMethod() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("noPropagate");
        assertFalse(AnnotationResolver.isAnnotationPresent(PropagateToken.class, method));
    }

    @Test
    void annotationDetectedOnClassAppliesToAllMethods() throws Exception {
        Method method = ClassLevelAnnotatedParticipant.class.getMethod("action");
        assertTrue(method.getDeclaringClass().isAnnotationPresent(PropagateToken.class));
    }

    // --- Full flow tests ---

    @Test
    void fullFlowWithMethodAnnotation() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer " + VALID_JWT);

        assertNotNull(Current.getAuthToken());

        String url = TestPortProvider.generateURL("/echo");
        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("Bearer " + VALID_JWT, response.readEntity(String.class));
            }
        }
    }

    @Test
    void fullFlowWithClassAnnotation() throws Exception {
        Method method = ClassLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer " + VALID_JWT);

        assertNotNull(Current.getAuthToken());

        String url = TestPortProvider.generateURL("/echo");
        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("Bearer " + VALID_JWT, response.readEntity(String.class));
            }
        }
    }

    @Test
    void unannotatedMethodDoesNotCaptureToken() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("noPropagate");
        simulateServerLRAFilter(method, "Bearer " + VALID_JWT);

        assertNull(Current.getAuthToken());

        String url = TestPortProvider.generateURL("/echo");
        try (Client client = ClientBuilder.newClient()) {
            client.register(JwtTokenClientRequestFilter.class);

            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                assertEquals("", response.readEntity(String.class));
            }
        }
    }

    // --- Structural validation tests ---

    @Test
    void nonBearerAuthHeaderIsIgnored() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Basic dXNlcjpwYXNz");

        assertNull(Current.getAuthToken());
    }

    @Test
    void malformedJwtIsRejected() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer not-a-jwt-at-all");

        assertNull(Current.getAuthToken(), "Token without three dot-separated segments should be rejected");
    }

    @Test
    void opaqueTokenIsRejected() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer abc123def456");

        assertNull(Current.getAuthToken(), "Opaque (non-JWT) Bearer token should be rejected");
    }

    @Test
    void twoSegmentTokenIsRejected() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer header.payload");

        assertNull(Current.getAuthToken(), "Two-segment token is not a valid JWS");
    }

    @Test
    void fourSegmentTokenIsRejected() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer a.b.c.d");

        assertNull(Current.getAuthToken(), "Four-segment token is not a valid JWS");
    }

    @Test
    void validThreeSegmentJwtIsAccepted() throws Exception {
        Method method = MethodLevelAnnotatedParticipant.class.getMethod("action");
        simulateServerLRAFilter(method, "Bearer header.payload.signature");

        assertNotNull(Current.getAuthToken());
        assertEquals("header.payload.signature", Current.getAuthToken());
    }

    // --- isPlausibleJwt unit tests ---

    @Test
    void isPlausibleJwtValidatesStructure() {
        assertTrue(BearerTokenResolver.isPlausibleJwt("a.b.c"));
        assertTrue(BearerTokenResolver.isPlausibleJwt("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.sig"));
        assertFalse(BearerTokenResolver.isPlausibleJwt("no-dots"));
        assertFalse(BearerTokenResolver.isPlausibleJwt("one.dot"));
        assertFalse(BearerTokenResolver.isPlausibleJwt("too.many.dots.here"));
        assertFalse(BearerTokenResolver.isPlausibleJwt(".empty.header"));
        assertFalse(BearerTokenResolver.isPlausibleJwt("empty..signature"));
    }
}
