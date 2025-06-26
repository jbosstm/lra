/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.resource;

import static io.narayana.lra.arquillian.resource.LRAMultipleParticipant2.LRA_PARTICIPANT_PATH;
import static org.eclipse.microprofile.lra.annotation.ParticipantStatus.Active;
import static org.eclipse.microprofile.lra.annotation.ParticipantStatus.FailedToCompensate;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_ENDED_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.MANDATORY;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path(LRA_PARTICIPANT_PATH)
public class LRAMultipleParticipant2 {
    public static final String LRA_PARTICIPANT_PATH = "participant2";
    public static final String TRANSACTIONAL_START_PATH = "start-work";

    private static final Logger log = Logger.getLogger(LRAMultipleParticipant2.class);

    private static final AtomicInteger forgetCount = new AtomicInteger(0);
    private static final AtomicInteger compensateCount = new AtomicInteger(0);

    @GET
    @Path(TRANSACTIONAL_START_PATH)
    @LRA(value = MANDATORY, end = false, timeLimit = 100)
    public Response start(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infov("\033[0;1m\u001B[34mJoining LRA with ID {0}", lraId);

        return Response.ok(lraId.toASCIIString()).build();
    }

    @PUT
    @Path("compensate")
    @Compensate
    public Response compensate(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) throws NotFoundException {
        log.infov("\033[0;1m\u001B[34m@Compensate called for {0}. Returning 500 to purposely fail the compensate!", lraId);

        compensateCount.incrementAndGet();

        return Response.status(500).entity(FailedToCompensate.name()).build();
    }

    @DELETE
    @Path("forget")
    @Forget
    public Response forget(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infov("\033[0;1m@Forget called for {0}. Returning OK.", lraId);

        forgetCount.incrementAndGet();

        return Response.ok().build();
    }

    @GET
    @Path("status")
    @Status
    public Response status(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infov("\033[0;1m\u001B[34m@Status called for {0}.", lraId);

        System.out.println(
                "xxxxxxxxxxx LRAMultipleParticipant2 Status called" +
                        " compensateCount" + compensateCount);

        if (compensateCount.get() == 1) {
            return Response.ok(FailedToCompensate.name()).build();
        }

        return Response.ok(Active.name()).build();
    }

    @PUT
    @Path("/after")
    @AfterLRA
    public Response after(@HeaderParam(LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus lraStatus) {
        log.infov("\033[0;1m\u001B[34m@AfterLRA called for {0} with LRAStatus {1}", lraId, lraStatus);

        return Response.ok().build();
    }

}
