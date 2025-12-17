/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import io.narayana.lra.arquillian.resource.LRAParticipantAfterLRA;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Optional;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class LRAParticipantAfterLRAIT extends TestBase {

    private static final Logger log = Logger.getLogger(LRAParticipantAfterLRAIT.class);

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
        return Deployer.deploy(LRAParticipantAfterLRAIT.class.getSimpleName(), LRAParticipantAfterLRA.class);
    }

    @Test
    public void testAfterLRACount() {
        Client client = ClientBuilder.newClient();
        Response response = null;
        try {
            response = client.target(UriBuilder.fromUri(baseURL.toExternalForm())
                    .path(LRAParticipantAfterLRA.SIMPLE_PARTICIPANT_RESOURCE_PATH)
                    .path(LRAParticipantAfterLRA.DO_LRA_PATH).build()).request().put(null);

            Assertions.assertEquals(200, response.getStatus());
            Assertions.assertTrue(response.hasEntity());

            if (response != null) {
                response.close();
            }

            response = client.target(UriBuilder.fromUri(baseURL.toExternalForm())
                    .path(LRAParticipantAfterLRA.SIMPLE_PARTICIPANT_RESOURCE_PATH)
                    .path(LRAParticipantAfterLRA.COUNTER_LRA_PATH).build()).request().get();

            Assertions.assertEquals(2, Integer.parseInt(response.readEntity(String.class)));
        } finally {
            if (response != null) {
                response.close();
            }
            if (client != null) {
                client.close();
            }
        }

    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }
}
