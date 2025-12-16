/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static io.narayana.lra.arquillian.resource.SimpleLRAParticipant.SIMPLE_PARTICIPANT_RESOURCE_PATH;
import static io.narayana.lra.arquillian.resource.SimpleLRAParticipant.START_LRA_PATH;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.arquillian.filter.LinkCapturingFilter;
import io.narayana.lra.arquillian.resource.SimpleLRAParticipant;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Optional;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Test that the Link header sent to coordinator can be changed with the custom base URI.
 */
public class LRACustomBaseURIIT extends TestBase {
    private static final Logger log = Logger.getLogger(LRACustomBaseURIIT.class);

    @ArquillianResource
    public URL baseURL;

    public String testName;

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(LRACustomBaseURIIT.class.getSimpleName(), SimpleLRAParticipant.class, LinkCapturingFilter.class)
                .addAsManifestResource(
                        new StringAsset("narayana.lra.base-uri=http://example.com"), "microprofile-config.properties");
    }

    @Test
    public void configBaseURLOverrideTest() throws Exception {
        try (Response response = client.target(baseURL.toURI())
                .path(SIMPLE_PARTICIPANT_RESOURCE_PATH)
                .path(START_LRA_PATH)
                .request()
                .get()) {

            System.out.println(response.getHeaderString("Link"));
            assertTrue(response.getHeaderString("Link").contains("http://example.com/"
                    + SimpleLRAParticipant.SIMPLE_PARTICIPANT_RESOURCE_PATH + "/compensate?method=jakarta.ws.rs.PUT"),
                    "The base URI was not overridden by the configuration");
        }
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }

}
