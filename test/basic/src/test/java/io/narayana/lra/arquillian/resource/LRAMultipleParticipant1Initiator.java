/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.resource;

import static io.narayana.lra.arquillian.resource.LRAMultipleParticipant1Initiator.LRA_PARTICIPANT_PATH;
import static org.eclipse.microprofile.lra.annotation.ParticipantStatus.Active;
import static org.eclipse.microprofile.lra.annotation.ParticipantStatus.Compensated;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_ENDED_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.REQUIRES_NEW;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
public class LRAMultipleParticipant1Initiator {
    public static final String LRA_PARTICIPANT_PATH = "participant1-initiator";
    public static final String TRANSACTIONAL_START_PATH = "start-work";
    public static final String LRA_END_STATUS = "lra-end-status";
    public static final String BASE_URL_PARAM = "base-url";

    private static final Logger log = Logger.getLogger(LRAMultipleParticipant1Initiator.class);

    private static final AtomicInteger forgetCount = new AtomicInteger(0);
    private static final AtomicInteger compensateCount = new AtomicInteger(0);
    private static final Map<URI, LRAStatus> status = new ConcurrentHashMap<>();

    @GET
    @Path(TRANSACTIONAL_START_PATH)
    @LRA(value = REQUIRES_NEW, timeLimit = 200)
    public Response start(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId, @QueryParam(BASE_URL_PARAM) URL baseUrl)
            throws URISyntaxException {
        log.infov("\033[0;1mStarting LRA with ID {0}", lraId);
        log.infov("\033[0;1mReceived base URL from client: {0}", baseUrl);

        WebTarget target = ClientBuilder.newClient()
                .target(baseUrl.toURI())
                .path(LRAMultipleParticipant2.LRA_PARTICIPANT_PATH)
                .path(LRAMultipleParticipant2.TRANSACTIONAL_START_PATH);

        log.infov("\033[0;1mCalling participant 2 at {0}", target.getUri());

        Response response = target
                .request()
                .header(LRA_HTTP_CONTEXT_HEADER, lraId)
                .get();

        log.infov("\033[0;1mReceived response from participant 2: {0}", response.readEntity(String.class));

        log.infov("\033[0;1mReturning status 500 to purposely fail the LRA!");
        return Response.status(500).entity(lraId.toASCIIString()).build();
    }

    @PUT
    @Path("compensate")
    @Compensate
    public Response compensate(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infov("\033[0;1m@Compensate called for {0}. Returning OK.", lraId);

        compensateCount.incrementAndGet();

        return Response.ok().build();
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
        log.infov("\033[0;1m@Status called for {0}.", lraId);

        if (compensateCount.get() == 1) {
            log.infov("\033[0;1mReturning OK with body Compensated");
            return Response.ok(Compensated.name()).build();
        }

        log.infov("\033[0;1mReturning OK with body Active");
        return Response.ok(Active.name()).build();
    }

    @PUT
    @Path("after")
    @AfterLRA
    public Response after(@HeaderParam(LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus lraStatus) {
        log.infov("\033[0;1m@AfterLRA called for {0} with LRAStatus {1}", lraId, lraStatus);

        status.put(lraId, lraStatus);

        return Response.ok().build();
    }

    @GET
    @Path(LRA_END_STATUS)
    public Response getLraStatus(@QueryParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        log.infov("\033[0;1mgetLraStatus() called for {0}.", lraId);

        if (!status.containsKey(lraId)) {
            log.infov("\033[0;1mAfterLRA not yet called. Returning 202");
            return Response.status(202).entity(lraId.toASCIIString() + "AfterLRA not yet called").build();
        }

        log.infov("\033[0;1mAfterLRA called. Returning 200 with {0} ", status.get(lraId).name());
        return Response.ok(status.get(lraId).name()).build();
    }

}
