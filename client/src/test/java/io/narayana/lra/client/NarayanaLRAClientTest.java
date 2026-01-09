package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class NarayanaLRAClientTest {

    @Test
    public void testConstructors() {
        try (NarayanaLRAClient client = new NarayanaLRAClient()) {
            assertEquals("http://localhost:8080/lra-coordinator", client.getCoordinatorUrl());
        }

        try (NarayanaLRAClient client = new NarayanaLRAClient("https", "test-url", 16663, "random-path")) {
            assertEquals("https://test-url:16663/random-path", client.getCoordinatorUrl());
        }

        try (NarayanaLRAClient client = new NarayanaLRAClient(URI.create("http://test-url/random-path"))) {
            assertEquals("http://test-url/random-path", client.getCoordinatorUrl());
        }

        try (NarayanaLRAClient client = new NarayanaLRAClient("http://test-url")) {
            assertEquals("http://test-url", client.getCoordinatorUrl());
        }
    }

    @Test
    public void testCoordinatorURLOverrideWithConfig() {
        String original = System.getProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY);
        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY, "http://test-url:16663/random-path");

        try (NarayanaLRAClient client = new NarayanaLRAClient()) {
            assertEquals("http://test-url:16663/random-path", client.getCoordinatorUrl());
        }

        if (original != null) {
            System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY, original);
        }

        System.out.println(System.getProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY));
    }
}
