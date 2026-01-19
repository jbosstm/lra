/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.resource;

import static io.narayana.lra.arquillian.resource.LRAAsyncParticipant2Client1.LRA_PARTICIPANT_PATH;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_ENDED_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.MANDATORY;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path(LRA_PARTICIPANT_PATH)
public class LRAAsyncParticipant2Client1 {
    public static final String LRA_PARTICIPANT_PATH = "participant2-client1";
    public static final String TRANSACTIONAL_START_PATH = "start-work";
    public static final String LRA_END_STATUS = "lra-end-status";

    private static final Logger log = Logger.getLogger(LRAAsyncParticipant2Client1.class);

    @GET
    @Path(TRANSACTIONAL_START_PATH)
    @LRA(value = MANDATORY, end = false, timeLimit = 100)
    public Response start(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infov("\033[0;1m\u001B[34mLRAAsyncParticipant2Client1 Joining LRA with ID {0}", lraId);

        return Response.ok("Client1").build();
    }

    @PUT
    @Path("/after")
    @AfterLRA
    public Response after(@HeaderParam(LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus lraStatus) {
        log.infov("\033[0;1m\u001B[34m@LRAAsyncParticipant2Client1 AfterLRA called for {0} with LRAStatus {1}", lraId,
                lraStatus);

        return Response.ok().build();
    }

}
