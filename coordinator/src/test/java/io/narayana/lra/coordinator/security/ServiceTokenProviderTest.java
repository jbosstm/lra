/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.narayana.lra.LRAConstants;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ServiceTokenProviderTest {

    private static UndertowJaxrsServer server;

    @TempDir
    static File tempDir;

    @Path("/token-endpoint")
    public static class MockTokenEndpoint {

        @GET
        @Path("/token")
        public Response token() {
            return Response.ok("http-service-token-value").build();
        }
    }

    @ApplicationPath("/")
    public static class TokenServerApp extends Application {

        @Override
        public Set<Class<?>> getClasses() {
            Set<Class<?>> classes = new HashSet<>();
            classes.add(MockTokenEndpoint.class);
            return classes;
        }
    }

    @BeforeAll
    static void setup() {
        server = new UndertowJaxrsServer().start();
        server.deploy(TokenServerApp.class);
    }

    @AfterAll
    static void teardown() {
        if (server != null) {
            server.stop();
        }
        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
        System.clearProperty(LRAConstants.SERVICE_TOKEN_REFRESH_SECONDS);
    }

    @Test
    void testReadTokenFromFile() throws Exception {
        File tokenFile = new File(tempDir, "token");
        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("file-based-token-value");
        }

        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, tokenFile.getAbsolutePath());

        ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
        assertNotNull(provider);
        assertEquals("file-based-token-value", provider.getToken());

        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
    }

    @Test
    void testReadTokenFromFileUri() throws Exception {
        File tokenFile = new File(tempDir, "token-uri");
        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("file-uri-token-value\n");
        }

        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, "file://" + tokenFile.getAbsolutePath());

        ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
        assertNotNull(provider);
        assertEquals("file-uri-token-value", provider.getToken());

        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
    }

    @Test
    void testReadTokenFromHttp() {
        String httpUrl = TestPortProvider.generateURL("/token-endpoint/token");
        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, httpUrl);

        ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
        assertNotNull(provider);
        assertEquals("http-service-token-value", provider.getToken());

        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
    }

    @Test
    void testTokenIsCached() throws Exception {
        File tokenFile = new File(tempDir, "cached-token");
        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("original-token");
        }

        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, tokenFile.getAbsolutePath());
        System.setProperty(LRAConstants.SERVICE_TOKEN_REFRESH_SECONDS, "3600");

        ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
        assertEquals("original-token", provider.getToken());

        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("rotated-token");
        }

        assertEquals("original-token", provider.getToken(),
                "Cached token should be returned before refresh interval");

        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
        System.clearProperty(LRAConstants.SERVICE_TOKEN_REFRESH_SECONDS);
    }

    @Test
    void testFromConfigReturnsNullWithoutLocation() {
        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);

        ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
        assertNull(provider, "Provider should be null when location is not configured");
    }

    @Test
    void testRecoveryThreadGetsServiceToken() throws Exception {
        File tokenFile = new File(tempDir, "recovery-token");
        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("recovery-service-token");
        }

        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, tokenFile.getAbsolutePath());

        Thread recoveryThread = new Thread(() -> {
            ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
            assertNotNull(provider);
            assertEquals("recovery-service-token", provider.getToken());
        });

        recoveryThread.start();
        recoveryThread.join();
        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
    }

    @Test
    void testNewClientUsesServiceTokenWhenNoInboundToken() throws Exception {
        File tokenFile = new File(tempDir, "newclient-token");
        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("newclient-service-token");
        }

        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, tokenFile.getAbsolutePath());

        try (Client client = JwtTokenContext.newClient()) {
            Object tokenProperty = client.getConfiguration().getProperty(LRAConstants.BEARER_TOKEN_PROPERTY);
            assertNotNull(tokenProperty,
                    "newClient() should read service token when no inbound token exists");
        }

        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
    }

    @Test
    void testTokenTrimsWhitespace() throws Exception {
        File tokenFile = new File(tempDir, "whitespace-token");
        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write("  clean-token  \n");
        }

        System.setProperty(LRAConstants.SERVICE_TOKEN_LOCATION, tokenFile.getAbsolutePath());

        ServiceTokenProvider provider = ServiceTokenProvider.fromConfig();
        assertEquals("clean-token", provider.getToken());

        System.clearProperty(LRAConstants.SERVICE_TOKEN_LOCATION);
    }
}
