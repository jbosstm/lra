/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.participant;

import static io.narayana.lra.proxy.test.api.LRAMgmtEgController.GET_ACTIVITY_PATH;
import static io.narayana.lra.proxy.test.api.LRAMgmtEgController.LRAM_PATH;
import static io.narayana.lra.proxy.test.api.LRAMgmtEgController.LRAM_WORK;
import static org.junit.Assert.assertTrue;

import io.narayana.lra.client.internal.NarayanaLRAClient;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URL;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SpecTest {
    private static URL MICROSERVICE_BASE_URL;

    private static final int TEST_PORT = 8081;

    private static NarayanaLRAClient lraClient;
    private static Client msClient;

    private WebTarget msTarget;

    @BeforeClass
    public static void setupClass() throws Exception {
        System.out.println("Getting ready to connect - waiting for coordinator to startup...");
        int servicePort = Integer.getInteger("service.http.port", TEST_PORT);

        MICROSERVICE_BASE_URL = new URL("http://localhost:" + servicePort);

        // setting up the client
        lraClient = new NarayanaLRAClient();
        msClient = ClientBuilder.newClient();
    }

    @Before
    public void setupTest() throws Exception {
        setupClass();
        msTarget = msClient.target(URI.create(new URL(MICROSERVICE_BASE_URL, "/").toExternalForm()));
    }

    @After
    public void tearDown() {
        if (lraClient != null) {
            lraClient.close();
        }

        lraClient = null;
    }

    @Test
    public void testLRAMgmt() {
        URI lraId = lraClient.startLRA("testStartLRA");

        Response response = msTarget.path(LRAM_PATH).path(LRAM_WORK)
                .queryParam("lraId", lraId.toASCIIString())
                .request().put(Entity.text(""));

        String activityId = checkStatusAndClose(response, Response.Status.OK.getStatusCode(), true);

        try {
            lraClient.closeLRA(lraId);
        } catch (Error e) {
            e.printStackTrace();
        }

        response = msTarget.path(LRAM_PATH).path(GET_ACTIVITY_PATH)
                .queryParam("activityId", activityId)
                .request()
                .get();

        String activity = response.readEntity(String.class);
        checkStatusAndClose(response, Response.Status.OK.getStatusCode(), false);

        // validate that the service received the complete call
        assertTrue(activity.contains("status=Completed"));
    }

    private String checkStatusAndClose(Response response, int expected, boolean readEntity) {
        try (response) {
            if (expected != -1 && response.getStatus() != expected) {
                throw new WebApplicationException(response);
            }

            if (readEntity) {
                return response.readEntity(String.class);
            }
        }

        return null;
    }
}
