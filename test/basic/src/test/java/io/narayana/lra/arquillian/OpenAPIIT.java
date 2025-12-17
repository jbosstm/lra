/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static java.lang.System.getProperty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class OpenAPIIT extends TestBase {
    private static final Logger log = Logger.getLogger(OpenAPIIT.class);

    public String testName;

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(OpenAPIIT.class.getSimpleName());
    }

    @Test
    public void test() throws URISyntaxException, MalformedURLException {
        URL url = new URL("http://"
                + getProperty("lra.coordinator.host", "localhost") + ":"
                + getProperty("lra.coordinator.port", "8080") + "/openapi");
        Response response = client.target(url.toURI()).request().get();
        String output = response.readEntity(String.class);
        assertFalse(output.contains("/lra-coordinator/lra-coordinator:"),
                "WildFly OpenAPI document has paths at wrong location");
        assertTrue(output.contains("/lra-coordinator:"),
                "WildFly OpenAPI document does not have paths for expected location:\n" + output);
        assertTrue(output.contains("url: /lra-coordinator"),
                "WildFly OpenAPI document does not have server URL");
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }
}
