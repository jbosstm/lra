package io.narayana.lra.arquillian.resource;

import io.narayana.lra.client.NarayanaLRAClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path(TestClientResource.PATH)
@ApplicationScoped
public class TestClientResource {

    public static final String PATH = "test-client-resource";

    @GET
    public String getClientURL() {
        // create default client that should pick the URL from MP config
        try (var client = new NarayanaLRAClient()) {
            return client.getCoordinatorUrl();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get coordinator URL", e);
        }
    }

}
