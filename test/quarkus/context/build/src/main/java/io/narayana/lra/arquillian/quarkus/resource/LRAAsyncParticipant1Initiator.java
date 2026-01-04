/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.arquillian.quarkus.resource;

import static io.narayana.lra.arquillian.quarkus.resource.LRAAsyncParticipant1Initiator.LRA_PARTICIPANT_PATH;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_ENDED_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.REQUIRES_NEW;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path(LRA_PARTICIPANT_PATH)
public class LRAAsyncParticipant1Initiator {

    public static final String LRA_PARTICIPANT_PATH = "participant1-initiator";
    public static final String TRANSACTIONAL_START_PATH = "start-work";
    public static final String LRA_END_STATUS = "lra-end-status";
    public static final String BASE_URL_PARAM = "base-url";

    public static final String LRA_PARTICIPANT2_PATH = "participant2-client1";
    public static final String TRANSACTIONAL2_START_PATH = "start-work";

    public static final String LRA_PARTICIPANT3_PATH = "participant3-client2";
    public static final String TRANSACTIONAL3_START_PATH = "start-work";

    private static final Logger log = Logger.getLogger(LRAAsyncParticipant1Initiator.class);

    private static final Map<URI, LRAStatus> status = new ConcurrentHashMap<>();

    @Inject
    ManagedExecutor managedExecutorService;

    @GET
    @Path(TRANSACTIONAL_START_PATH)
    @LRA(value = REQUIRES_NEW, timeLimit = 200)
    public Response start(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId, @QueryParam(BASE_URL_PARAM) URL baseUrl)
            throws URISyntaxException, InterruptedException, ExecutionException {
        log.infov("\033[0;1m\u001B[33mStarting LRA with ID {0}", lraId);
        log.infov("\033[0;1m\u001B[33mReceived base URL from client: {0}", baseUrl);

        log.infov("\033[0;1m\u001B[33mInitiator calling client 1 at {0}{1}/{2}", baseUrl.toURI(), LRA_PARTICIPANT2_PATH,
                TRANSACTIONAL2_START_PATH);

        Future<Response> futureResponse1 = managedExecutorService.submit(() -> ClientBuilder.newClient()
                .target(baseUrl.toURI())
                .path(LRA_PARTICIPANT2_PATH)
                .path(TRANSACTIONAL2_START_PATH)
                .request()
                .get());

        log.infov("\033[0;1m\u001B[33mInitiator calling client 2 at {0}{1}/{2}", baseUrl.toURI(), LRA_PARTICIPANT3_PATH,
                TRANSACTIONAL3_START_PATH);

        Future<Response> futureResponse2 = managedExecutorService.submit(() -> ClientBuilder.newClient()
                .target(baseUrl.toURI())
                .path(LRA_PARTICIPANT3_PATH)
                .path(TRANSACTIONAL3_START_PATH)
                .request()
                .get());

        // Small sleep to give the logging some chance to appear in order. Not technically needed.
        Thread.sleep(200);

        String response1 = futureResponse1.get().readEntity(String.class);

        log.infov("\033[0;1m\u001B[33mInitiator received response from client 1: {0}", response1);

        String response2 = futureResponse2.get().readEntity(String.class);

        log.infov("\033[0;1m\u001B[33mInitiator received response from client 2: {0}", response2);

        if (!"Client1".equals(response1) || !"Client2".equals(response2)) {
            log.infov("\033[0;1m\u001B[33m Unexpected responses: initiator returning status 500");
            return Response.status(500).entity(lraId.toASCIIString()).build();
        }

        log.infov("\033[0;1m\u001B[33mExpected responses: initiator returning status 200");
        return Response.status(200).entity(lraId.toASCIIString()).build();

    }

    @PUT
    @Path("after")
    @AfterLRA
    public Response after(@HeaderParam(LRA_HTTP_ENDED_CONTEXT_HEADER) URI lraId, LRAStatus lraStatus) {
        log.infov("\033[0;1m\u001B[33m@Initiator AfterLRA called for {0} with LRAStatus {1}", lraId, lraStatus);

        status.put(lraId, lraStatus);

        return Response.ok().build();
    }

}
