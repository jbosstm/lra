/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static io.narayana.lra.arquillian.resource.LRAMultipleParticipant1Initiator.BASE_URL_PARAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.arquillian.resource.LRAAsyncParticipant1Initiator;
import io.narayana.lra.arquillian.resource.LRAAsyncParticipant2Client1;
import io.narayana.lra.arquillian.resource.LRAAsyncParticipant3Client2;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class LRAAsyncLRAIT extends TestBase {

    private static final Logger log = Logger.getLogger(LRAAsyncLRAIT.class);

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
        return Deployer.deploy(
                LRAAsyncLRAIT.class.getSimpleName(),
                LRAAsyncParticipant1Initiator.class,
                LRAAsyncParticipant2Client1.class,
                LRAAsyncParticipant3Client2.class);
    }

    @Test
    public void testAfterLRACount() {
        log.infov("\033[0;1mTest starting LRA test with URL {0}", baseURL);

        try (Client client = ClientBuilder.newClient()) {
            try (Response response = client.target(UriBuilder.fromUri(baseURL.toExternalForm())
                    .path(LRAAsyncParticipant1Initiator.LRA_PARTICIPANT_PATH)
                    .path(LRAAsyncParticipant1Initiator.TRANSACTIONAL_START_PATH)
                    .queryParam(BASE_URL_PARAM, baseURL)
                    .build())
                    .request()
                    .get()) {

                assertEquals(200, response.getStatus());
                assertTrue(response.hasEntity());
            }
        }
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }
}
