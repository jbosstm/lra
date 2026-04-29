/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.logging.LRALogger;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Reads a pre-provisioned JWT token from a file, classpath resource, or HTTP endpoint.
 * Used by {@link JwtTokenContext#newClient()} as a fallback when no inbound Bearer
 * token is available (e.g., on the recovery thread).
 *
 * <p>
 * The token is cached and re-read from the source after a configurable interval
 * (default 300 seconds) to support external rotation (e.g., Kubernetes projected
 * volumes, Vault Agent sidecars).
 *
 * <p>
 * Supported location schemes:
 * <ul>
 * <li>{@code file:///var/run/secrets/token} — filesystem path</li>
 * <li>{@code classpath://META-INF/service-token} — classpath resource</li>
 * <li>{@code http://} or {@code https://} — HTTP GET to a token endpoint</li>
 * <li>Plain path (no scheme) — treated as a filesystem path</li>
 * </ul>
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code lra.security.service-token.location} — token source (required)</li>
 * <li>{@code lra.security.service-token.refresh-seconds} — cache TTL in seconds (default 300)</li>
 * </ul>
 */
final class ServiceTokenProvider {

    private static final int DEFAULT_REFRESH_SECONDS = 300;

    private final String location;
    private final long refreshMillis;

    private String cachedToken;
    private long expiresAtMillis;

    private ServiceTokenProvider(String location, long refreshSeconds) {
        this.location = location;
        this.refreshMillis = refreshSeconds * 1000L;
    }

    static ServiceTokenProvider fromConfig() {
        String location = getStringConfig(LRAConstants.SERVICE_TOKEN_LOCATION);

        if (location == null) {
            return null;
        }

        long refreshSeconds = getLongConfig(LRAConstants.SERVICE_TOKEN_REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS);

        LRALogger.logger.info(LRALogger.i18nLogger.info_serviceTokenProviderConfigured(location, refreshSeconds));

        return new ServiceTokenProvider(location, refreshSeconds);
    }

    synchronized String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < expiresAtMillis) {
            return cachedToken;
        }

        try {
            cachedToken = readToken();
            expiresAtMillis = System.currentTimeMillis() + refreshMillis;
        } catch (Exception e) {
            LRALogger.i18nLogger.warn_failedToReadServiceToken(location, e.getMessage(), e);
            cachedToken = null;
        }

        return cachedToken;
    }

    private String readToken() throws Exception {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return readFromHttp();
        }

        try (InputStream is = openResource()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private InputStream openResource() throws Exception {
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
            return new FileInputStream(location);
        }
    }

    private String readFromHttp() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(location).toURL().openConnection();

        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();

            if (status != 200) {
                throw new RuntimeException("Token endpoint returned HTTP " + status);
            }

            return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } finally {
            connection.disconnect();
        }
    }

    private static String getStringConfig(String key) {
        try {
            String value = ConfigProvider.getConfig().getValue(key, String.class);
            return (value != null && !value.isEmpty()) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static long getLongConfig(String key, long defaultValue) {
        try {
            return ConfigProvider.getConfig().getValue(key, Long.class);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
