/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import io.narayana.lra.logging.LRALogger;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

/**
 * Internal utility class for configuring REST clients with security and timeout settings.
 * Reads configuration from MicroProfile Config properties with the prefix "lra.http-client.*"
 *
 * <p>
 * Supported configuration properties:
 * </p>
 * <ul>
 * <li>lra.http-client.trustStore - Path to truststore file (supports file:// and classpath:// schemes)</li>
 * <li>lra.http-client.trustStorePassword - Truststore password</li>
 * <li>lra.http-client.trustStoreType - Truststore type (JKS, PKCS12), default: JKS</li>
 * <li>lra.http-client.keyStore - Path to keystore file for mutual TLS</li>
 * <li>lra.http-client.keyStorePassword - Keystore password</li>
 * <li>lra.http-client.keyStoreType - Keystore type, default: JKS</li>
 * <li>lra.http-client.hostnameVerifier - Fully qualified class name of custom HostnameVerifier</li>
 * <li>lra.http-client.connectTimeout - Connection timeout in milliseconds</li>
 * <li>lra.http-client.readTimeout - Read timeout in milliseconds</li>
 * <li>lra.http-client.providers - Comma-separated list of provider class names to register</li>
 * </ul>
 */
public class RestClientConfig {
    private static final String CONFIG_PREFIX = "lra.http-client.";
    private static final String TRUSTSTORE_KEY = CONFIG_PREFIX + "trustStore";
    private static final String TRUSTSTORE_PASSWORD_KEY = CONFIG_PREFIX + "trustStorePassword";
    private static final String TRUSTSTORE_TYPE_KEY = CONFIG_PREFIX + "trustStoreType";
    private static final String KEYSTORE_KEY = CONFIG_PREFIX + "keyStore";
    private static final String KEYSTORE_PASSWORD_KEY = CONFIG_PREFIX + "keyStorePassword";
    private static final String KEYSTORE_TYPE_KEY = CONFIG_PREFIX + "keyStoreType";
    private static final String HOSTNAME_VERIFIER_KEY = CONFIG_PREFIX + "hostnameVerifier";
    private static final String CONNECT_TIMEOUT_KEY = CONFIG_PREFIX + "connectTimeout";
    private static final String READ_TIMEOUT_KEY = CONFIG_PREFIX + "readTimeout";
    private static final String PROVIDERS_KEY = CONFIG_PREFIX + "providers";

    private static final String DEFAULT_KEYSTORE_TYPE = "JKS";

    private final Config config;

    /**
     * Creates a new RestClientConfig using the default MicroProfile Config
     */
    public RestClientConfig() {
        this(ConfigProvider.getConfig());
    }

    /**
     * Creates a new RestClientConfig with a specific Config instance
     *
     * @param config the MicroProfile Config to use
     */
    public RestClientConfig(Config config) {
        this.config = config;
    }

    /**
     * Configures a RestClientBuilder with all applicable security and timeout settings
     * from the MicroProfile Config properties.
     *
     * @param builder the RestClientBuilder to configure
     * @return the configured RestClientBuilder
     */
    public RestClientBuilder configure(RestClientBuilder builder) {
        configureSSL(builder);
        configureTimeouts(builder);
        configureProviders(builder);
        return builder;
    }

    /**
     * Configures SSL/TLS settings on the builder
     */
    private void configureSSL(RestClientBuilder builder) {
        try {
            SSLContext sslContext = createSSLContext();
            if (sslContext != null) {
                builder.sslContext(sslContext);
            }

            HostnameVerifier hostnameVerifier = loadHostnameVerifier();
            if (hostnameVerifier != null) {
                builder.hostnameVerifier(hostnameVerifier);
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to configure SSL for REST client: %s", e.getMessage());
        }
    }

    /**
     * Creates an SSLContext from the configured truststore and keystore
     */
    private SSLContext createSSLContext() throws Exception {
        String trustStoreLocation = getConfigValue(TRUSTSTORE_KEY);
        String keyStoreLocation = getConfigValue(KEYSTORE_KEY);

        // If neither truststore nor keystore is configured, return null to use defaults
        if (trustStoreLocation == null && keyStoreLocation == null) {
            return null;
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");

        TrustManagerFactory trustManagerFactory = null;
        if (trustStoreLocation != null) {
            String trustStorePassword = getConfigValue(TRUSTSTORE_PASSWORD_KEY);
            String trustStoreType = getConfigValue(TRUSTSTORE_TYPE_KEY, DEFAULT_KEYSTORE_TYPE);

            KeyStore trustStore = loadKeyStore(trustStoreLocation, trustStorePassword, trustStoreType);
            trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
        }

        KeyManagerFactory keyManagerFactory = null;
        if (keyStoreLocation != null) {
            String keyStorePassword = getConfigValue(KEYSTORE_PASSWORD_KEY);
            String keyStoreType = getConfigValue(KEYSTORE_TYPE_KEY, DEFAULT_KEYSTORE_TYPE);

            KeyStore keyStore = loadKeyStore(keyStoreLocation, keyStorePassword, keyStoreType);
            keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keyStorePassword != null ? keyStorePassword.toCharArray() : null);
        }

        sslContext.init(
                keyManagerFactory != null ? keyManagerFactory.getKeyManagers() : null,
                trustManagerFactory != null ? trustManagerFactory.getTrustManagers() : null,
                null);

        return sslContext;
    }

    /**
     * Loads a KeyStore from the specified location
     * Supports file:// and classpath:// URI schemes
     */
    private KeyStore loadKeyStore(String location, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);

        try (InputStream inputStream = openResource(location)) {
            keyStore.load(inputStream, password != null ? password.toCharArray() : null);
        }

        return keyStore;
    }

    /**
     * Opens an InputStream for a resource location
     * Supports file://, classpath://, and plain filesystem paths
     */
    private InputStream openResource(String location) throws Exception {
        if (location.startsWith("classpath://")) {
            String resourcePath = location.substring("classpath://".length());
            InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
            if (stream == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resourcePath);
            }
            return stream;
        } else if (location.startsWith("file://")) {
            return new FileInputStream(location.substring("file://".length()));
        } else {
            // Assume filesystem path for backward compatibility
            return new FileInputStream(location);
        }
    }

    /**
     * Loads a custom HostnameVerifier from the configured class name
     */
    private HostnameVerifier loadHostnameVerifier() {
        String className = getConfigValue(HOSTNAME_VERIFIER_KEY);
        if (className == null) {
            return null;
        }

        try {
            Class<?> clazz = Class.forName(className);
            return (HostnameVerifier) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to load HostnameVerifier class %s: %s",
                    className, e.getMessage());
            return null;
        }
    }

    /**
     * Configures connection and read timeouts on the builder
     */
    private void configureTimeouts(RestClientBuilder builder) {
        Long connectTimeout = getConfigValueAsLong(CONNECT_TIMEOUT_KEY);
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout, TimeUnit.MILLISECONDS);
        }

        Long readTimeout = getConfigValueAsLong(READ_TIMEOUT_KEY);
        if (readTimeout != null) {
            builder.readTimeout(readTimeout, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Registers custom providers on the builder
     */
    private void configureProviders(RestClientBuilder builder) {
        String providers = getConfigValue(PROVIDERS_KEY);
        if (providers == null || providers.trim().isEmpty()) {
            return;
        }

        for (String providerClassName : providers.split(",")) {
            String trimmed = providerClassName.trim();
            if (!trimmed.isEmpty()) {
                try {
                    Class<?> providerClass = Class.forName(trimmed);
                    builder.register(providerClass);
                } catch (Exception e) {
                    LRALogger.logger.warnf(e, "Failed to load provider class %s: %s",
                            trimmed, e.getMessage());
                }
            }
        }
    }

    /**
     * Gets a configuration value as a String
     */
    private String getConfigValue(String key) {
        return getConfigValue(key, null);
    }

    /**
     * Gets a configuration value as a String with a default
     */
    private String getConfigValue(String key, String defaultValue) {
        try {
            String value = config.getValue(key, String.class);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        } catch (Exception ignore) {
        }
        return defaultValue;
    }

    /**
     * Gets a configuration value as a Long
     */
    private Long getConfigValueAsLong(String key) {
        try {
            return config.getValue(key, Long.class);
        } catch (Exception ignore) {
            return null;
        }
    }
}
