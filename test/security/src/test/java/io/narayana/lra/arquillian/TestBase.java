/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import io.narayana.lra.LRAData;
import io.narayana.lra.client.NarayanaLRAClient;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
}
