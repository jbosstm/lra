/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.participant;

import static io.narayana.lra.proxy.test.api.LRAMgmtEgController.GET_ACTIVITY_PATH;
import static io.narayana.lra.proxy.test.api.LRAMgmtEgController.LRAM_PATH;
import static io.narayana.lra.proxy.test.api.LRAMgmtEgController.LRAM_WORK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.Deployer;
import io.narayana.lra.client.NarayanaLRAClient;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a non-JAX-RS participant registered through the LRA proxy
 * ({@link io.narayana.lra.client.internal.proxy.ProxyService}) receives the completion callback when the
 * enclosing LRA is closed, as required by the MicroProfile LRA specification for a successfully completed LRA.
 */
@RunAsClient
@ExtendWith(ArquillianExtension.class)
public class SpecIT {

    private static NarayanaLRAClient lraClient;
    private static Client msClient;

    @ArquillianResource
    private URL microserviceBaseUrl;

    private WebTarget msTarget;
    private URI lraId;

    @Deployment
    public static WebArchive deploy() {
        return Deployer.createDeployment();
    }

    @BeforeAll
    public static void beforeAll() {
        lraClient = new NarayanaLRAClient();
        msClient = ClientBuilder.newClient();
    }

    @AfterAll
    public static void afterAll() {
        if (lraClient != null) {
            lraClient.close();
        }
        if (msClient != null) {
            msClient.close();
        }
    }

    @AfterEach
    public void tearDown() {
        // safety net: cancel the LRA if a failing test left it active
        if (lraId != null) {
            List<URI> activeLRAs = lraClient.getAllLRAs().stream()
                    .map(LRAData::getLraId)
                    .collect(Collectors.toList());
            if (activeLRAs.contains(lraId)) {
                lraClient.cancelLRA(lraId);
            }
            lraId = null;
        }
    }

    @Test
    public void testLRAMgmt() throws URISyntaxException {
        msTarget = msClient.target(microserviceBaseUrl.toURI());

        lraId = lraClient.startLRA("SpecIT#testLRAMgmt");

        String activityId;
        try (Response response = msTarget.path(LRAM_PATH).path(LRAM_WORK)
                .queryParam("lraId", lraId.toASCIIString())
                .request().put(Entity.text(""))) {
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(),
                    "enlisting the proxy participant should succeed");
            activityId = response.readEntity(String.class);
        }

        // closing the LRA must drive the proxy participant to completion
        lraClient.closeLRA(lraId);

        String activity;
        try (Response response = msTarget.path(LRAM_PATH).path(GET_ACTIVITY_PATH)
                .queryParam("activityId", activityId)
                .request()
                .get()) {
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(),
                    "querying the activity should succeed");
            activity = response.readEntity(String.class);
        }

        // validate that the participant received the complete call
        assertTrue(activity.contains("status=Completed"),
                "the proxy participant should have been completed, but the activity was: " + activity);

        lraId = null;
    }
}
