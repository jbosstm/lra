/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.arquillian.quarkus.resource.LRAAsyncParticipant2Client1;
import io.narayana.lra.arquillian.quarkus.resource.LRAAsyncParticipant3Client2;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.net.URL;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class LRAAsyncLRAIT extends TestBase {

    public static final String BASE_URL_PARAM = "base-url";

    public static final String LRA_PARTICIPANT_PATH = "participant1-initiator";
    public static final String TRANSACTIONAL_START_PATH = "start-work";

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(LRAAsyncLRAIT.class.getName());

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
                LRAAsyncParticipant2Client1.class,
                LRAAsyncParticipant3Client2.class);
    }

    @Test
    public void testAfterLRACount() {
        System.out.println("\033[0;1mTest starting LRA test with URL " + baseURL);

        URI initiatorStartURI = UriBuilder.newInstance()
                .scheme("http")
                .host(System.getProperty("lra.initiator.host"))
                .port(Integer.valueOf(System.getProperty("lra.initiator.port")))
                .path(LRA_PARTICIPANT_PATH)
                .path(TRANSACTIONAL_START_PATH)
                .queryParam(BASE_URL_PARAM, baseURL)
                .build();

        System.out.println("\033[0;1mCalling QUARKUS initiator at " + initiatorStartURI);

        try (Client client = ClientBuilder.newClient()) {
            try (Response response = client.target(initiatorStartURI)
                    .request()
                    .get()) {

                assertEquals(200, response.getStatus());
                assertTrue(response.hasEntity());

                System.out.println("\033[0;1mTest received 200 status from QUARKUS initiator.");
            }
        }

    }

}
