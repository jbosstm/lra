package io.narayana.lra.arquillian.api;

import static io.narayana.lra.arquillian.api.NoopParticipant.NOOP_PARTICIPANT_PATH;

import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path(NOOP_PARTICIPANT_PATH)
public class NoopParticipant {

    public static final String NOOP_PARTICIPANT_PATH = "noop-participant";

    @PUT
    public Response afterLra() {
        return Response.ok().build();
    }

}
