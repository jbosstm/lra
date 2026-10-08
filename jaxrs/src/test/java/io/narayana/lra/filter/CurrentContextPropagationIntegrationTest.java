/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.filter;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.narayana.lra.Current;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the "current LRA" (the MicroProfile LRA {@code LRA_HTTP_CONTEXT_HEADER}, per the spec section on
 * context propagation) is resolved from the JAX-RS runtime's propagated request context rather than a raw
 * {@link ThreadLocal}.
 *
 * <p>
 * The MicroProfile LRA specification does not require that a JAX-RS request filter and the target resource method run
 * on the same thread. When they do not, a {@link ThreadLocal} set by the filter is either invisible to the resource
 * method or, worse, a pooled thread carries a previous request's LRA into an unrelated request. These tests deploy a
 * real RESTEasy/Undertow application and drive requests through a filter that writes the LRA into the request header
 * (exactly as {@code ServerLRAFilter} does), then assert that {@link Current#peek()} — the single accessor behind
 * {@code NarayanaLRAClient.getCurrent()} — returns the correct per-request LRA.
 *
 * <p>
 * Running in this module exercises the real {@link ResteasyLRAContextResolver}, discovered by {@link Current} through
 * the {@code META-INF/services/io.narayana.lra.spi.LRAContextResolver} registration on the classpath, so the
 * implementation-neutral SPI seam is covered end to end rather than a test double.
 *
 * <p>
 * A stale/foreign {@link ThreadLocal} is simulated deterministically by having the request filter push an unrelated
 * LRA onto {@link Current} at the start of the request; this reproduces the state a leaked pooled thread would be in at
 * the moment the resource method reads the context, without relying on thread-pool timing.
 */
public class CurrentContextPropagationIntegrationTest {

    private static final URI CURRENT_LRA = URI.create("http://localhost:8080/lra-coordinator/current-lra");
    private static final URI STALE_LRA = URI.create("http://localhost:8080/lra-coordinator/stale-lra");
    private static final URI PARENT_LRA = URI.create("http://localhost:8080/lra-coordinator/parent-lra");
    private static final URI NESTED_LRA = URI.create("http://localhost:8080/lra-coordinator/nested-lra");
    private static final URI INCOMING_LRA = URI.create("http://localhost:8080/lra-coordinator/incoming-lra");

    private static UndertowJaxrsServer server;

    /**
     * Applies request state the way {@code ServerLRAFilter} would, driven by query parameters so a single deployment
     * can exercise every scenario:
     * <ul>
     * <li>{@code stale} - push this LRA onto {@link Current} before the resource runs (a leaked/foreign ThreadLocal,
     * or the context a non-transactional method pushes without a header); {@code cache=true} also registers it in
     * the active-LRA cache so the ThreadLocal fallback branch keeps it.</li>
     * <li>{@code header} - write this LRA into the request {@code LRA_HTTP_CONTEXT_HEADER}; {@code pushHeader=true}
     * also pushes it onto {@link Current}, mirroring the normal in-request state.</li>
     * </ul>
     */
    @Provider
    public static class ContextSeedingRequestFilter implements ContainerRequestFilter {

        @Override
        public void filter(ContainerRequestContext requestContext) {
            MultivaluedMap<String, String> query = requestContext.getUriInfo().getQueryParameters();

            String stale = query.getFirst("stale");
            if (stale != null && !stale.isEmpty()) {
                URI staleLRA = URI.create(stale);
                Current.push(staleLRA);
                if ("true".equals(query.getFirst("cache"))) {
                    Current.addActiveLRACache(staleLRA);
                }
            }

            String header = query.getFirst("header");
            if (header != null && !header.isEmpty()) {
                requestContext.getHeaders().putSingle(LRA_HTTP_CONTEXT_HEADER, header);
                if ("true".equals(query.getFirst("pushHeader"))) {
                    Current.push(URI.create(header));
                }
            }
        }
    }

    /**
     * Clears {@link Current} after each request so the pooled serving thread starts the next request clean; each test
     * re-seeds its own state via {@link ContextSeedingRequestFilter}.
     */
    @Provider
    public static class ContextCleanupResponseFilter implements ContainerResponseFilter {

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            // when asked, exercise the real response-header writer (as ServerLRAFilter does) before cleaning up, so a
            // test can assert what is echoed to the caller
            if ("true".equals(requestContext.getUriInfo().getQueryParameters().getFirst("echo"))) {
                Current.updateLRAContext(responseContext);
            }

            Current.popAll();
        }
    }

    @Path("/ctx")
    public static class ContextResource {

        /**
         * Optionally pushes a programmatic nested LRA (as {@code NarayanaLRAClient.startLRA} would), then reports both
         * the reconciled {@link Current#peek()} and the raw LRA read from the propagated {@link HttpHeaders}, as
         * {@code peek=<uri|NULL>;header=<uri|NULL>}.
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public Response current(@QueryParam("nested") String nested) {
            if (nested != null && !nested.isEmpty()) {
                Current.push(URI.create(nested));
            }

            URI peek = Current.peek();

            HttpHeaders propagated = ResteasyContext.getContextData(HttpHeaders.class);
            String header = propagated == null ? null : Current.getLast(propagated.getRequestHeader(LRA_HTTP_CONTEXT_HEADER));

            String body = "peek=" + (peek == null ? "NULL" : peek.toString())
                    + ";header=" + (header == null ? "NULL" : header);

            return Response.ok(body).build();
        }
    }

    @ApplicationPath("/")
    public static class TestApp extends Application {

        @Override
        public Set<Class<?>> getClasses() {
            Set<Class<?>> classes = new HashSet<>();
            classes.add(ContextResource.class);
            classes.add(ContextSeedingRequestFilter.class);
            classes.add(ContextCleanupResponseFilter.class);
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

    private static String invoke(String queryString) {
        String url = TestPortProvider.generateURL("/ctx") + queryString;
        try (Client client = ClientBuilder.newClient()) {
            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                return response.readEntity(String.class);
            }
        }
    }

    private static List<String> invokeForContextHeader(String queryString) {
        String url = TestPortProvider.generateURL("/ctx") + queryString;
        try (Client client = ClientBuilder.newClient()) {
            try (Response response = client.target(url).request().get()) {
                assertEquals(200, response.getStatus());
                return response.getStringHeaders().get(LRA_HTTP_CONTEXT_HEADER);
            }
        }
    }

    /**
     * Load-bearing assumption: an {@code LRA_HTTP_CONTEXT_HEADER} written by a request filter into
     * {@code ContainerRequestContext.getHeaders()} is visible to the resource via the propagated request context — i.e.
     * both views share the same request headers.
     */
    @Test
    void filterWrittenHeaderIsVisibleViaPropagatedContext() {
        String body = invoke("?header=" + CURRENT_LRA);

        assertEquals("peek=" + CURRENT_LRA + ";header=" + CURRENT_LRA, body,
                "The LRA context header set by the request filter must be readable from the propagated request context");
    }

    /**
     * Core fix: when the serving thread carries a stale/foreign LRA in the {@link ThreadLocal} but the request resolved
     * a different LRA (present in the propagated header), {@link Current#peek()} must return the request's LRA, never
     * the leaked one.
     */
    @Test
    void staleThreadLocalIsIgnoredWhenHeaderIsPresent() {
        String body = invoke("?stale=" + STALE_LRA + "&header=" + CURRENT_LRA);

        assertEquals("peek=" + CURRENT_LRA + ";header=" + CURRENT_LRA, body,
                "A leaked ThreadLocal LRA must not shadow the LRA propagated for the current request");
    }

    /**
     * A programmatic nested LRA started inside the resource method (which pushes onto {@link Current} without rewriting
     * the header) must be honoured: because the header LRA is still on the stack, the live top is returned.
     */
    @Test
    void programmaticNestedPushIsHonoured() {
        String body = invoke("?header=" + PARENT_LRA + "&pushHeader=true&nested=" + NESTED_LRA);

        assertEquals("peek=" + NESTED_LRA + ";header=" + PARENT_LRA, body,
                "A nested LRA pushed inside the resource must be reported as current while the parent stays in the header");
    }

    /**
     * When no header is propagated (e.g. a non-transactional method that pushed an incoming context after the filter
     * removed the header, or pure client use), {@link Current#peek()} falls back to the ThreadLocal, guarded by the
     * active-LRA cache.
     */
    @Test
    void fallsBackToThreadLocalWhenNoHeaderIsPresent() {
        String body = invoke("?stale=" + INCOMING_LRA + "&cache=true");

        assertEquals("peek=" + INCOMING_LRA + ";header=NULL", body,
                "Without a propagated header the current LRA must come from the (cache-guarded) ThreadLocal");
    }

    /**
     * With neither a propagated header nor any ThreadLocal context, there is no current LRA.
     */
    @Test
    void noContextYieldsNoCurrentLRA() {
        String body = invoke("");

        assertEquals("peek=NULL;header=NULL", body,
                "With no header and no ThreadLocal context there must be no current LRA");
    }

    /**
     * Response-path counterpart of {@link #staleThreadLocalIsIgnoredWhenHeaderIsPresent()}: the LRA echoed back to the
     * caller in the response {@code LRA_HTTP_CONTEXT_HEADER} must be the LRA resolved for the request, never the leaked
     * one still sitting on the pooled thread's {@link ThreadLocal}.
     */
    @Test
    void responseEchoesResolvedLraNotStaleThreadLocal() {
        List<String> echoed = invokeForContextHeader("?stale=" + STALE_LRA + "&header=" + CURRENT_LRA + "&echo=true");

        assertEquals(List.of(CURRENT_LRA.toString()), echoed,
                "the response must echo the resolved LRA and must not leak the stale ThreadLocal LRA");
    }

    /**
     * When the {@link ThreadLocal} genuinely owns the current request's LRA, the response echoes the full stack so a
     * programmatic nested push and its parent are both propagated to the caller.
     */
    @Test
    void responseEchoesFullHierarchyWhenThreadLocalOwnsTheLra() {
        List<String> echoed = invokeForContextHeader(
                "?header=" + PARENT_LRA + "&pushHeader=true&nested=" + NESTED_LRA + "&echo=true");

        assertEquals(List.of(PARENT_LRA.toString(), NESTED_LRA.toString()), echoed,
                "when the ThreadLocal owns the LRA the response must echo the parent and the nested LRA in order");
    }
}
