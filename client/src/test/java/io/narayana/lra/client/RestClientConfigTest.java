package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.internal.RestClientConfig;
import jakarta.ws.rs.core.Configuration;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.Converter;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RestClientConfigTest {

    private Config mockConfig;
    private File tempKeyStore;
    private File tempTrustStore;

    @BeforeEach
    public void setUp() throws Exception {
        // Create temporary keystores for testing
        tempKeyStore = File.createTempFile("test-keystore", ".jks");
        tempTrustStore = File.createTempFile("test-truststore", ".jks");

        // Create a simple empty keystore
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
    }

    @Test
    public void testConfigureWithNoProperties() {
        Map<String, String> properties = new HashMap<>();
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("http://localhost:8080"));

        // Should not throw exception when no properties are set
        assertDoesNotThrow(() -> config.configure(builder));
    }

    @Test
    public void testConfigureWithTrustStore() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.trustStore", "file://" + tempTrustStore.getAbsolutePath());
        properties.put("lra.http-client.trustStorePassword", "testpass");
        properties.put("lra.http-client.trustStoreType", "JKS");
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        // Should configure SSL context successfully
        assertDoesNotThrow(() -> config.configure(builder));
    }

    @Test
    public void testConfigureWithKeyStore() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.keyStore", "file://" + tempKeyStore.getAbsolutePath());
        properties.put("lra.http-client.keyStorePassword", "testpass");
        properties.put("lra.http-client.keyStoreType", "JKS");
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        // Should configure SSL context successfully
        assertDoesNotThrow(() -> config.configure(builder));
    }

    @Test
    public void testConfigureWithBothKeyStoreAndTrustStore() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.trustStore", "file://" + tempTrustStore.getAbsolutePath());
        properties.put("lra.http-client.trustStorePassword", "testpass");
        properties.put("lra.http-client.trustStoreType", "JKS");
        properties.put("lra.http-client.keyStore", "file://" + tempKeyStore.getAbsolutePath());
        properties.put("lra.http-client.keyStorePassword", "testpass");
        properties.put("lra.http-client.keyStoreType", "JKS");
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        // Should configure SSL context with both keystores successfully
        assertDoesNotThrow(() -> config.configure(builder));
    }

    @Test
    public void testConfigureWithTimeouts() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.connectTimeout", "5000");
        properties.put("lra.http-client.readTimeout", "10000");
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        TestRestClientBuilder builder = new TestRestClientBuilder();
        builder.baseUri(URI.create("http://localhost:8080"));

        config.configure(builder);

        // Verify timeouts were set (through our test builder)
        assertEquals(5000L, builder.getConnectTimeout());
        assertEquals(10000L, builder.getReadTimeout());
    }

    @Test
    public void testConfigureWithCustomHostnameVerifier() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.hostnameVerifier", TestHostnameVerifier.class.getName());
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        TestRestClientBuilder builder = new TestRestClientBuilder();
        builder.baseUri(URI.create("https://localhost:8080"));

        config.configure(builder);

        // Verify hostname verifier was set
        assertNotNull(builder.getHostnameVerifier());
        assertTrue(builder.getHostnameVerifier() instanceof TestHostnameVerifier);
    }

    @Test
    public void testConfigureWithInvalidHostnameVerifier() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.hostnameVerifier", "com.example.NonExistentClass");
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        // Should not throw exception, just log a warning
        assertDoesNotThrow(() -> config.configure(builder));
    }

    @Test
    public void testConfigureWithProviders() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.providers", TestProvider.class.getName());
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        TestRestClientBuilder builder = new TestRestClientBuilder();
        builder.baseUri(URI.create("http://localhost:8080"));

        config.configure(builder);

        // Verify provider was registered
        assertTrue(builder.getRegisteredClasses().contains(TestProvider.class));
    }

    @Test
    public void testConfigureWithMultipleProviders() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.providers",
                TestProvider.class.getName() + "," + AnotherTestProvider.class.getName());
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        TestRestClientBuilder builder = new TestRestClientBuilder();
        builder.baseUri(URI.create("http://localhost:8080"));

        config.configure(builder);

        // Verify both providers were registered
        assertTrue(builder.getRegisteredClasses().contains(TestProvider.class));
        assertTrue(builder.getRegisteredClasses().contains(AnotherTestProvider.class));
    }

    @Test
    public void testConfigureWithInvalidTrustStore() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.trustStore", "file:///non/existent/path.jks");
        properties.put("lra.http-client.trustStorePassword", "testpass");
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        // Should not throw exception, configuration errors are logged as warnings
        assertDoesNotThrow(() -> config.configure(builder));
    }

    @Test
    public void testDefaultKeyStoreType() {
        Map<String, String> properties = new HashMap<>();
        properties.put("lra.http-client.trustStore", "file://" + tempTrustStore.getAbsolutePath());
        properties.put("lra.http-client.trustStorePassword", "testpass");
        // Not setting trustStoreType - should default to JKS
        mockConfig = new TestConfig(properties);

        RestClientConfig config = new RestClientConfig(mockConfig);
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create("https://localhost:8080"));

        // Should work with default JKS type
        assertDoesNotThrow(() -> config.configure(builder));
    }

    // Test helper classes

    public static class TestHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    public static class TestProvider {
        // Empty test provider
    }

    public static class AnotherTestProvider {
        // Another empty test provider
    }

    /**
     * Simple mock Config implementation for testing
     */
    private static class TestConfig implements Config {
        private final Map<String, String> properties;

        public TestConfig(Map<String, String> properties) {
            this.properties = properties;
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            String value = properties.get(propertyName);
            if (value == null) {
                throw new java.util.NoSuchElementException("Property " + propertyName + " not found");
            }
            return convertValue(value, propertyType);
        }

        @Override
        public <T> java.util.Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String value = properties.get(propertyName);
            if (value == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(convertValue(value, propertyType));
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return properties.keySet();
        }

        @Override
        public Iterable<org.eclipse.microprofile.config.spi.ConfigSource> getConfigSources() {
            return java.util.Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        private <T> T convertValue(String value, Class<T> type) {
            if (type == String.class) {
                return (T) value;
            } else if (type == Long.class || type == long.class) {
                return (T) Long.valueOf(value);
            } else if (type == Integer.class || type == int.class) {
                return (T) Integer.valueOf(value);
            }
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            // TODO Auto-generated method stub
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            // TODO Auto-generated method stub
            return null;
        }
    }

    /**
     * Test implementation of RestClientBuilder that tracks configuration
     */
    private static class TestRestClientBuilder implements RestClientBuilder {
        private URI baseUri;
        private Long connectTimeout;
        private Long readTimeout;
        private HostnameVerifier hostnameVerifier;
        private SSLContext sslContext;
        private final java.util.Set<Class<?>> registeredClasses = new java.util.HashSet<>();

        @Override
        public RestClientBuilder baseUri(URI uri) {
            this.baseUri = uri;
            return this;
        }

        @Override
        public RestClientBuilder baseUrl(java.net.URL url) {
            try {
                return baseUri(url.toURI());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public RestClientBuilder connectTimeout(long timeout, TimeUnit unit) {
            this.connectTimeout = unit.toMillis(timeout);
            return this;
        }

        @Override
        public RestClientBuilder readTimeout(long timeout, TimeUnit unit) {
            this.readTimeout = unit.toMillis(timeout);
            return this;
        }

        @Override
        public RestClientBuilder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        @Override
        public RestClientBuilder hostnameVerifier(HostnameVerifier hostnameVerifier) {
            this.hostnameVerifier = hostnameVerifier;
            return this;
        }

        @Override
        public RestClientBuilder register(Class<?> componentClass) {
            registeredClasses.add(componentClass);
            return this;
        }

        @Override
        public RestClientBuilder register(Class<?> componentClass, int priority) {
            return register(componentClass);
        }

        @Override
        public RestClientBuilder register(Class<?> componentClass, Class<?>... contracts) {
            return register(componentClass);
        }

        @Override
        public RestClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            return register(componentClass);
        }

        @Override
        public RestClientBuilder register(Object component) {
            registeredClasses.add(component.getClass());
            return this;
        }

        @Override
        public RestClientBuilder register(Object component, int priority) {
            return register(component);
        }

        @Override
        public RestClientBuilder register(Object component, Class<?>... contracts) {
            return register(component);
        }

        @Override
        public RestClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
            return register(component);
        }

        @Override
        public RestClientBuilder property(String name, Object value) {
            return this;
        }

        @Override
        public <T> T build(Class<T> aClass) {
            throw new UnsupportedOperationException("build() not implemented in test builder");
        }

        public Long getConnectTimeout() {
            return connectTimeout;
        }

        public Long getReadTimeout() {
            return readTimeout;
        }

        public HostnameVerifier getHostnameVerifier() {
            return hostnameVerifier;
        }

        public SSLContext getSslContext() {
            return sslContext;
        }

        public java.util.Set<Class<?>> getRegisteredClasses() {
            return registeredClasses;
        }

        @Override
        public Configuration getConfiguration() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder executorService(ExecutorService executor) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder trustStore(KeyStore trustStore) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder keyStore(KeyStore keyStore, String keystorePassword) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder followRedirects(boolean follow) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder proxyAddress(String proxyHost, int proxyPort) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder queryParamStyle(QueryParamStyle style) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public RestClientBuilder header(String name, Object value) {
            // TODO Auto-generated method stub
            return null;
        }
    }
}
