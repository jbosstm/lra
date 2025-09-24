/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.narayana.lra.arquillian.resource.TestClientResource;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;

/**
 * Test that the Narayana LRA client base coordinator URL can be overridden by configuration.
 */
public class NarayanaLRAClientConfigIT extends TestBase {
    private static final Logger log = Logger.getLogger(NarayanaLRAClientConfigIT.class);

    @ArquillianResource
    public URL baseURL;

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(NarayanaLRAClientConfigIT.class.getSimpleName(),
                TestClientResource.class, CustomConfigSourceOverrider.class)
                .addAsResource(new StringAsset(CustomConfigSourceOverrider.class.getName()),
                        "META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource");
    }

    /**
     * Custom configuration source that overrides the coordinator URL.
     * This is used to test if the configuration can be overridden correctly
     * because we set the lra.coordinator.url with the system property in arquillian.xml
     * so it cannot be overridden by the microprofile-config-properties file which has less priority.
     */
    public static final class CustomConfigSourceOverrider implements ConfigSource {
        private static final Map<String, String> config = new HashMap<>();

        static {
            config.put("lra.coordinator.url", "http://example-coordinator.com:35553/lra-coordinator");
        }

        @Override
        public int getOrdinal() {
            return Integer.MAX_VALUE; // ensure this config source is used first
        }

        @Override
        public Set<String> getPropertyNames() {
            return config.keySet();
        }

        @Override
        public String getValue(String s) {
            return config.get(s);
        }

        @Override
        public String getName() {
            return CustomConfigSourceOverrider.class.getSimpleName();
        }
    }

    @Test
    public void configCoordinatorURLOverrideInClientTest() throws Exception {
        try (Response response = client.target(baseURL.toURI())
                .path(TestClientResource.PATH)
                .request().get()) {
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(), "Unexpected returned status code");
            assertEquals("http://example-coordinator.com:35553/lra-coordinator", response.readEntity(String.class),
                    "The coordinator URL was not overridden by the configuration");
        }
    }

}
