/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Creates configured JAX-RS {@link Client} instances for outbound participant calls.
 *
 * <p>
 * Provider classes listed in the {@code lra.http-client.providers} MicroProfile Config
 * property are registered on every client produced by {@link #newClient()}. This is the
 * same configuration key used by {@code RestClientConfig} in the lra-client module,
 * so a single property controls both NarayanaLRAClient and coordinator outbound calls.
 *
 * <p>
 * Token resolution order in {@link #newClient()}:
 * <ol>
 * <li>Inbound token from {@code JsonWebToken} via CDI (propagated from the caller's request)</li>
 * <li>Service token from {@link ServiceTokenProvider} (for recovery threads with no
 * inbound request, configured via {@code lra.security.service-token.location})</li>
 * </ol>
 */
public final class JwtTokenContext {

    private static final String PROVIDERS_KEY = LRAConstants.HTTP_CLIENT_PROVIDERS;

    private static final List<Class<?>> providers = loadProviders();

    private static final ServiceTokenProvider serviceTokenProvider = ServiceTokenProvider.fromConfig();

    private JwtTokenContext() {
    }

    /**
     * Creates a new JAX-RS {@link Client} with all providers from the
     * {@code lra.http-client.providers} configuration registered.
     *
     * <p>
     * If a JWT token is available via CDI ({@code JsonWebToken} from the inbound
     * request), it is stored as a client configuration property. Otherwise, if a
     * {@link ServiceTokenProvider} is configured, a service token is read from the
     * configured location. This ensures that outbound calls from the recovery thread
     * (where no CDI request scope exists) can still authenticate to participants.
     */
    public static Client newClient() {
        Client client = ClientBuilder.newClient();

        String token = null;

        if (!providers.isEmpty()) {
            token = getTokenFromCDI();
        }

        if (token == null && serviceTokenProvider != null) {
            token = serviceTokenProvider.getToken();
        }

        if (token != null) {
            client.property(LRAConstants.BEARER_TOKEN_PROPERTY, token);
        }

        for (Class<?> provider : providers) {
            client.register(provider);
        }

        return client;
    }

    private static String getTokenFromCDI() {
        try {
            JsonWebToken jwt = CDI.current().select(JsonWebToken.class).get();
            String rawToken = jwt.getRawToken();
            if (rawToken != null) {
                if (LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.trace("JWT token resolved from CDI for outbound call");
                }
            }
            return rawToken;
        } catch (IllegalStateException e) {
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("CDI JsonWebToken not available: %s", e.getMessage());
            }
            return null;
        }
    }

    private static List<Class<?>> loadProviders() {
        String providersStr;

        try {
            providersStr = ConfigProvider.getConfig().getValue(PROVIDERS_KEY, String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }

        if (providersStr == null || providersStr.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Class<?>> result = new ArrayList<>();

        for (String className : providersStr.split(",")) {
            String trimmed = className.trim();

            if (!trimmed.isEmpty()) {
                try {
                    result.add(Class.forName(trimmed));
                } catch (Exception e) {
                    LRALogger.logger.warnf(e, "Failed to load provider class %s: %s",
                            trimmed, e.getMessage());
                }
            }
        }

        return Collections.unmodifiableList(result);
    }
}
