/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.quarkus;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.client.NarayanaLRAClient;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

@RunAsClient
@ExtendWith(ArquillianExtension.class)
public abstract class TestBase {

    public static NarayanaLRAClient lraClient;
    public static String coordinatorUrl;
    public static Client client;
    public static List<URI> lrasToAfterFinish;

    @BeforeAll
    public static void beforeClass() {
        lraClient = new NarayanaLRAClient();
        coordinatorUrl = lraClient.getCoordinatorUrl();
        client = ClientBuilder.newClient();
        lrasToAfterFinish = new ArrayList<>();
    }

    @AfterEach
    public void after() {
        List<URI> lraURIList = lraClient.getAllLRAs().stream().map(LRAData::getLraId).collect(Collectors.toList());
        if (lrasToAfterFinish != null) {
            for (URI lraToFinish : lrasToAfterFinish) {
                if (lraURIList.contains(lraToFinish)) {
                    lraClient.cancelLRA(lraToFinish);
                }
            }
        }
    }

    @AfterAll
    public static void afterAll() {
        if (client != null) {
            client.close();
        }
    }

    protected JsonArray getAllRecords(URI lra) {
        String coordinatorUrl = LRAConstants.getLRACoordinatorUrl(lra) + "/";

        try (Response response = client.target(coordinatorUrl).path("").request().get()) {
            Assertions.assertTrue(response.hasEntity(), "Missing response body when querying for all LRAs");
            String allLRAs = response.readEntity(String.class);

            JsonReader jsonReader = Json.createReader(new StringReader(allLRAs));
            return jsonReader.readArray();
        }
    }

}
