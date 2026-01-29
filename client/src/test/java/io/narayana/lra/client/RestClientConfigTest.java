package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.CompletionStage;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RestClientConfigTest {

    private static final String TEST_RESPONSE = "OK";
    private static final String PASSWORD = "changeit";
    private static final int HTTPS_PORT = 18443;
    private static final String SERVER_HOST = "localhost";

    private File tempKeyStore;
    private File tempTrustStore;
    private Undertow server;

    @BeforeEach
    public void setUp() throws Exception {
        tempKeyStore = File.createTempFile("test-keystore", ".jks");
        tempTrustStore = File.createTempFile("test-truststore", ".jks");

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, "testpass".toCharArray());

        try (FileOutputStream fos = new FileOutputStream(tempKeyStore)) {
            keyStore.store(fos, "testpass".toCharArray());
        }

        try (FileOutputStream fos = new FileOutputStream(tempTrustStore)) {
            keyStore.store(fos, "testpass".toCharArray());
        }
    }

    @AfterEach
    public void tearDown() {
        if (tempKeyStore != null && tempKeyStore.exists()) {
            tempKeyStore.delete();
        }
        if (tempTrustStore != null && tempTrustStore.exists()) {
            tempTrustStore.delete();
        }
        if (server != null) {
            server.stop();
        }
        System.clearProperty("lra.http-client.trustStore");
        System.clearProperty("lra.http-client.trustStorePassword");
        System.clearProperty("lra.http-client.trustStoreType");
        System.clearProperty("lra.http-client.keyStore");
        System.clearProperty("lra.http-client.keyStorePassword");
        System.clearProperty("lra.http-client.keyStoreType");
        System.clearProperty("lra.http-client.connectTimeout");
        System.clearProperty("lra.http-client.readTimeout");
        System.clearProperty("lra.http-client.hostnameVerifier");
        System.clearProperty("lra.http-client.providers");
    }

    @Test
    public void testConfigureWithNoProperties() {
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("http://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithTrustStore() {
        System.setProperty("lra.http-client.trustStore", "file://" + tempTrustStore.getAbsolutePath());
        System.setProperty("lra.http-client.trustStorePassword", "testpass");
        System.setProperty("lra.http-client.trustStoreType", "JKS");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithKeyStore() {
        System.setProperty("lra.http-client.keyStore", "file://" + tempKeyStore.getAbsolutePath());
        System.setProperty("lra.http-client.keyStorePassword", "testpass");
        System.setProperty("lra.http-client.keyStoreType", "JKS");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithBothKeyStoreAndTrustStore() {
        System.setProperty("lra.http-client.trustStore", "file://" + tempTrustStore.getAbsolutePath());
        System.setProperty("lra.http-client.trustStorePassword", "testpass");
        System.setProperty("lra.http-client.trustStoreType", "JKS");
        System.setProperty("lra.http-client.keyStore", "file://" + tempKeyStore.getAbsolutePath());
        System.setProperty("lra.http-client.keyStorePassword", "testpass");
        System.setProperty("lra.http-client.keyStoreType", "JKS");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithTimeouts() {
        System.setProperty("lra.http-client.connectTimeout", "5000");
        System.setProperty("lra.http-client.readTimeout", "10000");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("http://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithCustomHostnameVerifier() {
        System.setProperty("lra.http-client.hostnameVerifier",
                "io.narayana.lra.client.RestClientConfigTest$TestHostnameVerifier");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithInvalidHostnameVerifier() {
        System.setProperty("lra.http-client.hostnameVerifier", "com.example.NonExistentClass");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithProviders() {
        System.setProperty("lra.http-client.providers",
                "io.narayana.lra.client.RestClientConfigTest$TestProvider");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("http://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithMultipleProviders() {
        System.setProperty("lra.http-client.providers",
                "io.narayana.lra.client.RestClientConfigTest$TestProvider,"
                        + "io.narayana.lra.client.RestClientConfigTest$AnotherTestProvider");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("http://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testConfigureWithInvalidTrustStore() {
        System.setProperty("lra.http-client.trustStore", "file:///non/existent/path.jks");
        System.setProperty("lra.http-client.trustStorePassword", "testpass");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testDefaultKeyStoreType() {
        System.setProperty("lra.http-client.trustStore", "file://" + tempTrustStore.getAbsolutePath());
        System.setProperty("lra.http-client.trustStorePassword", "testpass");

        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        assertDoesNotThrow(() -> new RestClientConfig().configure(builder));
    }

    @Test
    public void testHttpsConnectionWithTrustStore() throws Exception {
        if (getClass().getClassLoader().getResource("client-truststore.jks") == null) {
            return;
        }

        startHttpsServer(false);

        System.setProperty("lra.http-client.trustStore",
                getClass().getClassLoader().getResource("client-truststore.jks").getPath());
        System.setProperty("lra.http-client.trustStorePassword", PASSWORD);
        System.setProperty("lra.http-client.trustStoreType", "JKS");
        System.setProperty("lra.http-client.hostnameVerifier",
                "io.narayana.lra.client.RestClientConfigTest$TestHostnameVerifier");

        RestClientBuilder builder = RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://" + SERVER_HOST + ":" + HTTPS_PORT));

        new RestClientConfig().configure(builder);

        TestApi client = builder.build(TestApi.class);
        Response response = client.test().toCompletableFuture().get();

        assertEquals(200, response.getStatus());
        assertEquals(TEST_RESPONSE, response.readEntity(String.class));
    }

    @Test
    public void testMutualTlsConnection() throws Exception {
        if (getClass().getClassLoader().getResource("client-truststore.jks") == null
                || getClass().getClassLoader().getResource("client-keystore.jks") == null) {
            return;
        }

        startHttpsServer(true);

        System.setProperty("lra.http-client.trustStore",
                getClass().getClassLoader().getResource("client-truststore.jks").getPath());
        System.setProperty("lra.http-client.trustStorePassword", PASSWORD);
        System.setProperty("lra.http-client.trustStoreType", "JKS");
        System.setProperty("lra.http-client.keyStore",
                getClass().getClassLoader().getResource("client-keystore.jks").getPath());
        System.setProperty("lra.http-client.keyStorePassword", PASSWORD);
        System.setProperty("lra.http-client.keyStoreType", "JKS");
        System.setProperty("lra.http-client.hostnameVerifier",
                "io.narayana.lra.client.RestClientConfigTest$TestHostnameVerifier");

        RestClientBuilder builder = RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://" + SERVER_HOST + ":" + HTTPS_PORT));

        new RestClientConfig().configure(builder);

        TestApi client = builder.build(TestApi.class);
        Response response = client.test().toCompletableFuture().get();

        assertEquals(200, response.getStatus());
        assertEquals(TEST_RESPONSE, response.readEntity(String.class));
    }

    @Test
    public void testConnectionWithoutTrustStoreFails() throws Exception {
        if (getClass().getClassLoader().getResource("server-keystore.jks") == null) {
            return;
        }

        startHttpsServer(false);

        System.setProperty("lra.http-client.hostnameVerifier",
                "io.narayana.lra.client.RestClientConfigTest$TestHostnameVerifier");

        RestClientBuilder builder = RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://" + SERVER_HOST + ":" + HTTPS_PORT));

        new RestClientConfig().configure(builder);

        TestApi client = builder.build(TestApi.class);

        assertThrows(Exception.class, () -> {
            Response response = client.test().toCompletableFuture().get();
            response.readEntity(String.class);
        });
    }

    @Test
    public void testMutualTlsWithoutClientCertFails() throws Exception {
        if (getClass().getClassLoader().getResource("client-truststore.jks") == null) {
            return;
        }

        startHttpsServer(true);

        System.setProperty("lra.http-client.trustStore",
                getClass().getClassLoader().getResource("client-truststore.jks").getPath());
        System.setProperty("lra.http-client.trustStorePassword", PASSWORD);
        System.setProperty("lra.http-client.trustStoreType", "JKS");
        System.setProperty("lra.http-client.hostnameVerifier",
                "io.narayana.lra.client.RestClientConfigTest$TestHostnameVerifier");

        RestClientBuilder builder = RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://" + SERVER_HOST + ":" + HTTPS_PORT));

        new RestClientConfig().configure(builder);

        TestApi client = builder.build(TestApi.class);

        assertThrows(Exception.class, () -> {
            Response response = client.test().toCompletableFuture().get();
            response.readEntity(String.class);
        });
    }

    private void startHttpsServer(boolean requireClientAuth) throws Exception {
        KeyStore keyStore = loadKeyStore("server-keystore.jks");
        KeyStore trustStore = loadKeyStore("server-truststore.jks");

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        HttpHandler handler = exchange -> {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send(TEST_RESPONSE);
        };

        Undertow.Builder builder = Undertow.builder()
                .addHttpsListener(HTTPS_PORT, SERVER_HOST, sslContext)
                .setHandler(handler);

        if (requireClientAuth) {
            builder.setSocketOption(org.xnio.Options.SSL_CLIENT_AUTH_MODE,
                    org.xnio.SslClientAuthMode.REQUIRED);
        }

        server = builder.build();
        server.start();
        Thread.sleep(500);
    }

    private KeyStore loadKeyStore(String resource) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + resource);
            }
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(is, PASSWORD.toCharArray());
            return keyStore;
        }
    }

    @Path("/")
    public interface TestApi {
        @GET
        @Path("/test")
        @Produces(MediaType.TEXT_PLAIN)
        CompletionStage<Response> test();
    }

    public static class TestHostnameVerifier implements javax.net.ssl.HostnameVerifier {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            return true;
        }
    }

    public static class TestProvider {
    }

    public static class AnotherTestProvider {
    }
}
