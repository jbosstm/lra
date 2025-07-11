/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_RECOVERY_HEADER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.narayana.lra.Current;
import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import io.narayana.lra.provider.ParticipantStatusOctetStreamProvider;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMScripts;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

@RunWith(BMUnitRunner.class)
public class LRATest extends LRATestBase {
    static final String LRA_API_VERSION_HEADER_NAME = "Narayana-LRA-API-version";
    static final String RECOVERY_HEADER_NAME = "Long-Running-Action-Recovery";
    private static LRAService service;

    private NarayanaLRAClient lraClient;
    private Client client;
    private String coordinatorPath;
    private String recoveryPath;
    int[] ports = { 8081, 8082 };

    @Rule
    public TestName testName = new TestName();

    @ApplicationPath("base")
    public static class LRAParticipant extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Participant.class);
            classes.add(Participant1.class);
            classes.add(Participant2.class);
            classes.add(AfterLRAListener.class);
            classes.add(ServerLRAFilter.class);
            classes.add(ParticipantStatusOctetStreamProvider.class);
            classes.add(BytemanHelper.class);
            return classes;
        }
    }

    @ApplicationPath("/")
    public static class LRACoordinator extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            return classes;
        }
    }

    @BeforeClass
    public static void start() {
        System.setProperty("lra.coordinator.url", TestPortProvider.generateURL('/' + COORDINATOR_PATH_NAME));
    }

    @Before
    public void before() {
        LRALogger.logger.debugf("Starting test %s", testName);
        clearObjectStore(testName);

        servers = new UndertowJaxrsServer[ports.length];

        StringBuilder sb = new StringBuilder();
        String host = "localhost";

        for (int i = 0; i < ports.length; i++) {
            servers[i] = new UndertowJaxrsServer().setHostname(host).setPort(ports[i]);
            try {
                servers[i].start();
            } catch (Exception e) {
                LRALogger.logger.infof("before test %s: could not start server %s",
                        testName.getMethodName(), e.getMessage());
            }

            sb.append(String.format("http://%s:%d/%s%s",
                    host, ports[i], COORDINATOR_PATH_NAME, i + 1 < ports.length ? "," : ""));
        }

        System.setProperty(NarayanaLRAClient.COORDINATOR_URLS_KEY, sb.toString());

        lraClient = new NarayanaLRAClient();

        compensateCount.set(0);
        completeCount.set(0);
        forgetCount.set(0);

        client = ClientBuilder.newClient();
        coordinatorPath = TestPortProvider.generateURL('/' + COORDINATOR_PATH_NAME);
        recoveryPath = coordinatorPath + "/recovery";

        for (UndertowJaxrsServer server : servers) {
            server.deploy(LRACoordinator.class);
            server.deployOldStyle(LRAParticipant.class);
        }

        service = LRARecoveryModule.getService();

        if (lraClient.getCurrent() != null) {
            // clear it since it isn't caused by this test (tests do the assertNull in the @After test method)
            LRALogger.logger.warnf("before test %s: current thread should not be associated with any LRAs",
                    testName.getMethodName());
            lraClient.clearCurrent(true);
        }
    }

    @After
    public void after() {
        URI uri = lraClient.getCurrent();
        try {
            if (uri != null) {
                lraClient.clearCurrent(false);
            }
            lraClient.close();
            client.close();
            clearObjectStore(testName);
        } catch (Exception e) {
            LRALogger.logger.infof("after test %s: clean up %s", testName, e.getMessage());
        } finally {
            for (UndertowJaxrsServer server : servers) {
                try {
                    server.stop();
                } catch (Exception e) {
                    LRALogger.logger.infof("after test %s: could not stop server %s", testName, e.getMessage());
                }
            }
            assertNull(testName.getMethodName() + ": current thread should not be associated with any LRAs",
                    uri);
        }
    }

    // test that it is safe to run periodic recovery scans on the LRARecoveryModule in parallel
    @Test
    public void testParallelScan() {
        final int LRA_COUNT = 3; // number of LRAs to add to the store
        final int SCAN_COUNT = 100; // number of parallel periodic recovery scans

        URI[] ids = new URI[LRA_COUNT];
        URI[] ids2 = new URI[SCAN_COUNT];

        try {
            // start LRA_COUNT LRAs so that there is something already in the store when the scans run
            IntStream.range(0, LRA_COUNT)
                    .forEach(i -> {
                        try {
                            ids[i] = lraClient.startLRA(testName.getMethodName() + i);
                            Current.pop(); // disassociate the LRA from the current thread
                            assertNotNull(ids[i]);
                        } catch (WebApplicationException e) {
                            fail(String.format("%s: step %d failed with HTTP status %d (%s)",
                                    testName.getMethodName(), i, e.getResponse().getStatus(), e.getMessage()));
                        }
                    });

            // run SCAN_COUNT recovery passes and LRAs in parallel to verify parallelism support
            IntStream.range(0, SCAN_COUNT)
                    .parallel()
                    .forEach(i -> {
                        try {
                            // start an LRA while a scan is in progress to test parallelism
                            ids2[i] = lraClient.startLRA(testName.getMethodName() + i);
                            service.scan();
                        } finally {
                            lraClient.cancelLRA(ids2[i]);
                        }
                    });
        } finally {
            // it's clearer and faster to use a standard for loop instead of the more elegant Java Streams API
            // Stream.of(ids).filter(Objects::nonNull).forEach(lra -> lraClient.cancelLRA(lra))
            for (int i = 0; i < LRA_COUNT; i++) {
                if (ids[i] != null) {
                    try {
                        lraClient.cancelLRA(ids[i]);
                    } catch (WebApplicationException e) {
                        System.out.printf("%s: cancel %s failed with %s%n",
                                testName.getMethodName(), ids[i], e.getMessage());
                    }
                }
            }
        }
    }

    @Test
    public void joinWithVersionTest() {
        URI lraId = lraClient.startLRA(testName.getMethodName());
        String version = LRAConstants.API_VERSION_1_2;
        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8); // must be valid

        try (Response response = client.target(coordinatorPath)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                // the request body should correspond to a valid compensator or be empty
                .put(Entity.text(""))) {
            Assert.assertEquals("Expected joining LRA succeeded, PUT/200 is expected.",
                    OK.getStatusCode(), response.getStatus());
            Assert.assertEquals("Expected API header to be returned with the version provided in request",
                    version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME));
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            URI recoveryUrlLocation = response.getLocation();
            Assert.assertEquals("Expecting returned body and recovery header have got the same content",
                    recoveryUrlBody, recoveryHeaderUrlMessage);
            Assert.assertEquals("Expecting returned body and location have got the same content",
                    recoveryUrlBody, recoveryUrlLocation.toString());
            MatcherAssert.assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
            // the new format just contains the Uid of the LRA
            MatcherAssert.assertThat("Expected returned message contains the LRA id",
                    recoveryUrlBody, containsString(LRAConstants.getLRAUid(lraId)));
        } finally {
            lraClient.cancelLRA(lraId);
        }
    }

    @Test
    public void joinWithOldVersionTest() {
        URI lraId = lraClient.startLRA(testName.getMethodName());
        String version = LRAConstants.API_VERSION_1_1;
        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8); // must be valid

        try (Response response = client.target(coordinatorPath)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                // the request body should correspond to a valid compensator or be empty
                .put(Entity.text(""))) {
            Assert.assertEquals("Expected joining LRA succeeded, PUT/200 is expected.",
                    OK.getStatusCode(), response.getStatus());
            Assert.assertEquals("Expected API header to be returned with the version provided in request",
                    version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME));
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            URI recoveryUrlLocation = response.getLocation();
            Assert.assertEquals("Expecting returned body and recovery header have got the same content",
                    recoveryUrlBody, recoveryHeaderUrlMessage);
            Assert.assertEquals("Expecting returned body and location have got the same content",
                    recoveryUrlBody, recoveryUrlLocation.toString());
            MatcherAssert.assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
            MatcherAssert.assertThat("Expected returned message contains the LRA id",
                    recoveryUrlBody, containsString(encodedLraId));
        } finally {
            lraClient.cancelLRA(lraId);
        }
    }

    /*
     * verify that participants are compensated in the reverse order from which they were enlisted with the LRA
     */
    @Test
    public void testParticipantCallbackOrderWithCancel() {
        participantCallbackOrder(true);
    }

    /*
     * verify that participants are completed in the reverse order from which they were enlisted with the LRA
     */
    @Test
    public void testParticipantCallbackOrderWithClose() {
        participantCallbackOrder(false);
    }

    void participantCallbackOrder(boolean cancel) {
        queue.clear(); // reset the queue which records the order in which participants are ended

        URI lraId = lraClient.startLRA(testName.getMethodName());

        for (int i = 1; i <= 2; i++) { // pick out participants participant1 and participant2
            String businessMethodName = String.format("/base/participant%d/continue", i);

            // invoke a method that has LRA.Type.MANDATORY passing in the LRA that was just started
            // (this will cause the participant to be enlisted with the LRA)
            try (Response r = client.target(TestPortProvider.generateURL(businessMethodName)).request()
                    .header(LRA_HTTP_CONTEXT_HEADER, lraId).get()) {
                if (r.getStatus() != OK.getStatusCode()) {
                    try {
                        // clean up and fail
                        lraClient.cancelLRA(lraId);

                        fail("could not reach participant" + i + ", status code=" + r.getStatus());
                    } catch (WebApplicationException e) {
                        fail(String.format("could not reach participant%d, status code=%d and %s failed with %s",
                                i, r.getStatus(), cancel ? "cancel" : "close", e.getMessage()));
                    }
                }
            }
        }

        if (cancel) {
            lraClient.cancelLRA(lraId);
        } else {
            lraClient.closeLRA(lraId);
        }

        // verify that participants participant1 and participant2 were compensated/completed in reverse order,
        // ie the queue should be in the order {2, 1} because they were enlisted in the order {1, 2}
        assertEquals(String.format("second participant should have %s first", cancel ? "compensated" : "completed"),
                Integer.valueOf(2), queue.remove()); // removing the first item from the queue should give participant2

        queue.remove(); // clean up item from participant1 (the remaining integer on the queue)
    }

    /**
     * sanity check: test that a participant is notified when an LRA closes
     */
    @Test
    public void testLRAParticipant() {
        // lookup the status of a non-existent LRA
        Response r1 = client.target(coordinatorPath + "/xyz/status").request().get();
        assertEquals("LRA id xyz should not exist", Response.Status.NOT_FOUND.getStatusCode(), r1.getStatus());

        // start a new LRA
        Response r2 = client.target(coordinatorPath + "/start").request().post(null);
        assertEquals("Expected 201", Response.Status.CREATED.getStatusCode(), r2.getStatus());
        String lraId = r2.getHeaderString(LRA_HTTP_CONTEXT_HEADER);
        Assert.assertNotNull("missing context header", lraId);
        // RestEasy adds brackets and , to delimit multiple values for a particular header key
        lraId = new StringTokenizer(lraId, "[,]").nextToken();
        // close the LRA
        Response r3 = client.target(String.format("%s/close", lraId)).request().put(null);
        int status = r3.getStatus();
        assertTrue("Problem closing LRA: ",
                status == OK.getStatusCode() || status == Response.Status.NOT_FOUND.getStatusCode());

        // verify that the participant complete request is issued when a method annotated with @LRA returns
        int completions = completeCount.get();
        client.target(TestPortProvider.generateURL("/base/test/start-end")).request().get(String.class);
        assertEquals(completions + 1, completeCount.get());
    }

    /**
     * sanity check: test that an LRA that closes is reported as closed or absent
     */
    @Test
    public void testComplete() throws URISyntaxException {
        // verify that the participant complete request is issued when a method annotated with @LRA returns
        int completions = completeCount.get();
        String lraId = client.target(TestPortProvider.generateURL("/base/test/start-end")).request().get(String.class);
        assertEquals(completions + 1, completeCount.get());
        LRAStatus status = getStatus(new URI(lraId));
        assertTrue("LRA should have closed", status == null || status == LRAStatus.Closed);
    }

    @Test
    // validate that the coordinator produces Json if the accepts header specifies Json
    public void testJsonContentNegotiation() {
        negotiateContent(MediaType.APPLICATION_JSON);
    }

    @Test
    // validate that the coordinator produces plain text if the accepts header specifies plain text
    public void testPlainContentNegotiation() {
        negotiateContent(MediaType.TEXT_PLAIN);
    }

    private void negotiateContent(String acceptMediaType) {
        URI lraId = lraClient.startLRA(testName.getMethodName());

        // cancel and validate that the response reports the status using the requested media type
        try (Response r = client
                .target(String.format("%s/cancel", lraId))
                .request()
                .accept(acceptMediaType)
                .put(null)) {

            int res = r.getStatus();

            if (res != OK.getStatusCode()) {
                fail("unable to cleanup: " + res);
            }

            try {
                if (acceptMediaType.equals(MediaType.TEXT_PLAIN)) {
                    String status = r.readEntity(String.class);

                    assertEquals(LRAStatus.Cancelled.name(), status);
                } else if (acceptMediaType.equals(MediaType.APPLICATION_JSON)) {
                    // {"status":"Active"}
                    String status = r.readEntity(String.class);
                    String expected = String.format("{\"status\":\"%s\"}", LRAStatus.Cancelled.name());

                    assertEquals(expected, status);
                }
            } catch (Exception e) {
                fail("Could not read entity body: " + e.getMessage());
            }
        }

        // we started the LRA using the client API which associates the LRA with the calling thread
        // and since the test then cancelled it directly, ie not via the API, the LRA will still be associated.
        // Therefore, it needs to be cleared otherwise subsequent tests will still see the LRA associated:
        lraClient.clearCurrent(false);
    }

    @Test
    public void testGetAllLRAsAcceptJson() {
        URI lraId = lraClient.startLRA(testName.getMethodName());

        // read all LRAs using Json
        try (Response response = client.target(coordinatorPath)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                .accept(MediaType.APPLICATION_JSON)
                .get()) {
            if (response.getStatus() != OK.getStatusCode()) {
                LRALogger.logger.debugf("Error getting all LRAs from the coordinator, response status: %d",
                        response.getStatus());
                throw new WebApplicationException(response);
            }

            String lrasAsJson = response.readEntity(String.class); // all LRAs as a json string

            try {
                // parse the json string into an array of LRAData
                LRAData[] lras = new ObjectMapper().readValue(lrasAsJson, LRAData[].class);
                // see if lraId is in the returned array
                Optional<LRAData> targetLRA = Arrays.stream(lras)
                        .filter(lra -> lraId.equals(lra.getLraId()))
                        .findFirst();

                assertTrue("The LRA is unknown to the coordinator", targetLRA.isPresent());
            } catch (JsonProcessingException e) {
                fail("could not read json array: " + e.getMessage());
            }
        } finally {
            try {
                lraClient.cancelLRA(lraId);
            } catch (WebApplicationException e) {
                fail("Could not clean up: " + e);
            }
        }
    }

    @Test
    // start an LRA and validate that the coordinator reports its status correctly
    public void testLRAInfoAcceptJson() {
        URI lraId = lraClient.startLRA(testName.getMethodName());

        // request the status of the LRA that was just started
        try (Response r = client
                .target(String.format("%s", lraId))
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .get()) {
            int res = r.getStatus();

            if (res != OK.getStatusCode()) {
                fail("unable to read LRAData: HTTP status was " + res);
            }

            String info = r.readEntity(String.class); // the entity body should be a Json representation of the LRA

            try {
                ObjectMapper objectMapper = new ObjectMapper();
                LRAData data = objectMapper.readValue(info, LRAData.class);
                // or Json.createReader(new StringReader(info)).readObject(); for the raw Json

                // validate the LRA id, the client id and the status
                assertEquals(lraId, data.getLraId());
                assertEquals(testName.getMethodName(), data.getClientId());
                assertEquals(LRAStatus.Active, data.getStatus());

            } catch (JsonProcessingException e) {
                fail("Unable to parse JSON response: " + info);
            }
        } finally {
            // clean up
            try {
                lraClient.cancelLRA(lraId);
            } catch (WebApplicationException e) {
                fail("Could not clean up: " + e);
            }
        }
    }

    @Test
    // start an LRA and validate that the coordinator reports its status correctly
    public void testLRAStatusWithJson() {
        URI lraId = lraClient.startLRA(testName.getMethodName());

        // request the status of the LRA that was just started
        try (Response r = client
                .target(String.format("%s/status", lraId))
                .request()
                .accept(MediaType.APPLICATION_JSON) // MediaType.TEXT_PLAIN, the default, is tested elsewhere
                .get()) {
            int res = r.getStatus();

            if (res != OK.getStatusCode()) {
                fail("unable to read LRAData: HTTP status was " + res);
            }

            String json = r.readEntity(String.class); // the entity body should be a Json representation of the LRA

            try {
                JsonNode node = new ObjectMapper().readTree(json);
                // read the value
                JsonNode n = node.get("status").get("string");
                String v = n.textValue();
                // or Json.createReader(new StringReader(info)).readObject(); for the raw Json

                // validate the LRA status
                assertEquals(LRAStatus.Active.name(), v);

            } catch (JsonProcessingException e) {
                fail("Unable to parse JSON response: " + json);
            }
        } finally {
            // clean up
            try {
                lraClient.cancelLRA(lraId);
            } catch (WebApplicationException e) {
                fail("Could not clean up: " + e);
            }
        }
    }

    @Test
    public void testStartAcceptJson() {
        try (Response response = client.target(coordinatorPath + "/start")
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, LRAConstants.CURRENT_API_VERSION_STRING)
                .accept(MediaType.APPLICATION_JSON)
                .post(null)) {
            if (response.getStatus() != OK.getStatusCode()) {
                LRALogger.logger.debugf("Error getting all LRAs from the coordinator, response status: %d",
                        response.getStatus());
                throw new WebApplicationException(response);
            }

            String json = "";
            URI lraId = null;

            try {
                json = response.readEntity(String.class);

                JsonNode node = new ObjectMapper().readTree(json);
                // read the value
                JsonNode n = node.get("lraId").get("string");
                String v = n.textValue();
                lraId = new URI(v);
                // or Json.createReader(new StringReader(info)).readObject(); for the raw Json

                // clean up
                lraClient.closeLRA(lraId);
            } catch (JsonProcessingException | URISyntaxException e) {
                fail("Unable to parse JSON response: " + json);
            } catch (WebApplicationException e) {
                fail("Unable to close lra: " + lraId);
            }
        }
    }

    @Test
    public void testJoinLRAViaBody() {
        URI lraId = lraClient.startLRA(testName.getMethodName());
        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8); // must be valid

        try (Response response = client.target(coordinatorPath)
                .path(encodedLraId)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                // the request body should correspond to a valid compensator or be empty
                .put(Entity.text(""))) {

            assertEquals(OK.getStatusCode(), response.getStatus());

            String recoveryUrl = null;

            try {
                String json = response.readEntity(String.class);
                JsonNode node = new ObjectMapper().readTree(json);
                // read the value
                JsonNode n = node.get("recoveryUrl");
                recoveryUrl = n.textValue();
            } catch (JsonProcessingException e) {
                fail("could not read json response: " + e.getMessage());
            }

            try {
                // just validate that the join request returned a valid URL
                new URI(recoveryUrl);
            } catch (URISyntaxException e) {
                fail("testJoinLRAViaBody returned an invalid recovery URL: " + recoveryUrl);
            }
        } finally {
            lraClient.closeLRA(lraId);
        }
    }

    /*
     * Participants can update their callbacks to facilitate recovery.
     * Test that the compensate endpoint can be changed:
     */
    @Test
    public void testReplaceCompensator() throws URISyntaxException {
        // verify that participants can change their callback endpoints
        int fallbackCompensations = fallbackCompensateCount.get();
        // call a participant method that starts an LRA and returns the lra and the recovery id in the response
        String urls = client.target(TestPortProvider.generateURL("/base/test/start-with-recovery")).request().get(String.class);
        String[] tokens = urls.split(",");
        assertTrue("response is missing components for the lraId and/or recoveryId",
                tokens.length >= 2);
        // the service method returns the lra and recovery ids in a comma separated response:
        String lraUrl = tokens[tokens.length - 2];
        String recoveryUrl = tokens[tokens.length - 1];

        // change the participant compensate endpoint (or change the resource completely to showcase migrating
        // responsibility for the participant to a different microservice
        String newCompensateCallback = TestPortProvider.generateURL("/base/test/fallback-compensate");
        // define the new link header for the new compensate endpoint
        String newCompensator = String.format("<%s>; rel=compensate", newCompensateCallback);

        // check that performing a GET on the recovery url returns the participant callbacks:
        try (Response r1 = client.target(recoveryUrl).request().get()) {
            int res = r1.getStatus();
            if (res != OK.getStatusCode()) {
                // clean up and fail
                fail("get recovery url failed: " + res);
            }

            String linkHeader = r1.readEntity(String.class);
            // the link header should be a standard link header corresponding to the participant callbacks,
            // just sanity check that the mandatory compensate rel type is present
            String compensateRelationType = "rel=\"compensate\"";

            MatcherAssert.assertThat("Compensator link header is missing the compensate rel type",
                    linkHeader, containsString(compensateRelationType));
        }

        // use the recovery url to ask the coordinator to compensate on a different endpoint
        try (Response r1 = client.target(recoveryUrl).request().put(Entity.text(newCompensator))) {
            int res = r1.getStatus();
            if (res != OK.getStatusCode()) {
                // clean up and fail
                try (Response r = client.target(String.format("%s/cancel", lraUrl)).request().put(null)) {
                    if (r.getStatus() != OK.getStatusCode()) {
                        fail("move and cancel failed");
                    }
                }
                fail("move failed");
            }
        }

        // cancel the LRA
        try (Response r2 = client.target(String.format("%s/cancel", lraUrl)).request().put(null)) {
            int res = r2.getStatus();
            if (res != OK.getStatusCode()) {
                fail("unable to cleanup: " + res);
            }
        }

        // verify that the participant was called on the new endpoint and that the LRA cancelled
        assertEquals(fallbackCompensations + 1, fallbackCompensateCount.get());
        LRAStatus status = getStatus(new URI(lraUrl));
        assertTrue("LRA should have cancelled", status == null || status == LRAStatus.Cancelled);
    }

    /**
     * Run a loop of LRAs so that a debugger can watch memory
     *
     * @throws URISyntaxException
     */
    @Test
    public void testForLeaks() throws URISyntaxException {
        int txnCount = 10;
        // verify that the participant complete request is issued when a method annotated with @LRA returns
        int completions = completeCount.get();

        // start some LRAs
        for (int i = 0; i < txnCount; i++) {
            String lraId = client.target(TestPortProvider.generateURL("/base/test/start-end")).request().get(String.class);
            LRAStatus status = getStatus(new URI(lraId));
            assertTrue("LRA should have closed", status == null || status == LRAStatus.Closed);
        }

        // Remark: there should be no memory leaks in LRAService

        assertEquals(completions + txnCount, completeCount.get());
    }

    /**
     * test that participants that report LRAStatus.Closing are replayed
     */
    @Test
    public void testReplay() {
        int completions = completeCount.get();
        Response response = client.target(TestPortProvider.generateURL("/base/test/start-end"))
                .queryParam("accept", "1")
                .request()
                .get();
        String lra = response.readEntity(String.class);
        URI lraId = null;

        try {
            lraId = new URI(lra);
        } catch (URISyntaxException e) {
            fail(String.format("%s: service returned an invalid URI (%s). Reason: %s)", testName, lra, e.getMessage()));
        }

        try {
            service.getLRA(lraId);
        } catch (NotFoundException e) {
            fail("testReplay: LRA should still have been completing: " + e.getMessage());
        }

        // the LRA should still be finishing (ie there should be a log record)
        assertEquals(completions, completeCount.get());

        service.recover();
        assertTrue(testName + ": lra did not finish", isFinished(lraId));
    }

    /**
     * test nested LRA behaviour when the parent closes
     */
    @Test
    public void testNestedLRA() {
        testNestedLRA(false, false, false, false);
    }

    /**
     * test nested LRA behaviour when the child cancels early
     */
    @Test
    public void testNestedLRAChildCancelsEarly() {
        testNestedLRA(true, false, false, false);
    }

    /**
     * test nested LRA behaviour when the parent and child both cancel early
     */
    @Test
    public void testNestedLRAChildAndParentCancelsEarly() {
        testNestedLRA(true, true, false, false);
    }

    /**
     * test nested LRA behaviour when the parent cancels
     */
    @Test
    public void testNestedLRAParentCancels() {
        testNestedLRA(false, true, false, false);
    }

    private void testNestedLRA(boolean childCancelEarly, boolean parentCancelEarly,
            boolean childCancelLate, boolean parentCancelLate) {
        // start a transaction (and cancel it if parentCancelEarly is true)
        Response parentResponse = client.target(TestPortProvider.generateURL("/base/test/start"))
                .queryParam("cancel", parentCancelEarly)
                .request().get();
        String parent = parentResponse.readEntity(String.class);

        if (parentCancelEarly) {
            assertEquals("invocation should have produced a 500 code", 500, parentResponse.getStatus());
            // and the parent and child should have compensated
            assertEquals("neither parent nor child should complete", 0, completeCount.get());
            assertEquals("parent and child should each have compensated", 1, compensateCount.get());
            assertStatus(parent, LRAStatus.Cancelled, true);

            return;
        }

        // start another transaction nested under parent (and cancel it if childCancelEarly is true)
        String child;

        try (Response childResponse = client.target(TestPortProvider.generateURL("/base/test/nested"))
                .queryParam("cancel", childCancelEarly)
                .request().header(LRA_HTTP_CONTEXT_HEADER, parent).put(Entity.text(parent))) {
            child = childResponse.readEntity(String.class);
            assertNotNull("start child failed: ", child);
            child = UriBuilder.fromUri(child).replaceQuery(null).build().toASCIIString();
        }

        if (childCancelEarly) {
            // the child is canceled and the parent is active
            assertEquals("neither parent nor child should complete", 0, completeCount.get());
            assertEquals("child should have compensated", 1, compensateCount.get());
            assertStatus(child, LRAStatus.Cancelled, true);
            assertStatus(parent, LRAStatus.Active, false);
        } else {
            assertEquals("nothing should be completed yet", 0, completeCount.get());
            assertEquals("nothing should be compensated yet", 0, compensateCount.get());
            assertStatus(parent, LRAStatus.Active, false);
            assertStatus(child, LRAStatus.Active, false);
        }

        // if the child was not cancelled then close it now
        if (childCancelEarly) {
            assertEquals("child should not have (provisionally) completed", 0, completeCount.get());
            assertEquals("child should have compensated", 1, compensateCount.get());
        } else {
            try (Response response = client.target(TestPortProvider.generateURL("/base/test/end"))
                    .queryParam("cancel", childCancelLate)
                    .request().header(LRA_HTTP_CONTEXT_HEADER, child).put(Entity.text(""))) {
                assertEquals("finish child: ", response.getStatus(), childCancelLate ? 500 : 200);
                assertEquals("child should have (provisionally) completed", 1, completeCount.get());
            }
        }

        // close the parent (remark: if parentCancelEarly then this code is not reached)
        try (Response response = client.target(TestPortProvider.generateURL("/base/test/end"))
                .queryParam("cancel", parentCancelLate)
                .request().header(LRA_HTTP_CONTEXT_HEADER, parent).put(Entity.text(""))) {
            assertEquals("finish parent", response.getStatus(), parentCancelLate ? 500 : 200);
        }

        try (Response response = client.target(TestPortProvider.generateURL("/base/test/forget-count"))
                .request()
                .get()) {

            assertEquals("LRA participant HTTP status", 200, response.getStatus());

            int forgetCount = response.readEntity(Integer.class);

            if (childCancelEarly) {
                assertEquals("A participant in a nested LRA that compensates should not be asked to forget",
                        0, forgetCount);
            } else {
                assertEquals("A participant in a nested LRA that completes should be asked to forget",
                        1, forgetCount);
            }
        }

        if (childCancelEarly) {
            assertEquals("parent should have completed and child should not have completed",
                    1, completeCount.get());
        } else {
            assertTrue("parent and child should have completed once each",
                    completeCount.get() >= 2);
        }
    }

    @Test
    public void completeMultiLevelNestedActivity() {
        try {
            multiLevelNestedActivity(CompletionType.complete, 1);
        } catch (URISyntaxException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void compensateMultiLevelNestedActivity() {
        try {
            multiLevelNestedActivity(CompletionType.compensate, 1);
        } catch (URISyntaxException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void mixedMultiLevelNestedActivity() {
        try {
            multiLevelNestedActivity(CompletionType.mixed, 2);
        } catch (URISyntaxException e) {
            fail(e.getMessage());
        }
    }

    private enum CompletionType {
        complete,
        compensate,
        mixed
    }

    private void multiLevelNestedActivity(CompletionType how, int nestedCnt)
            throws WebApplicationException, URISyntaxException {
        WebTarget resourcePath = client.target(TestPortProvider.generateURL("/base/test/multiLevelNestedActivity"));

        if (how == CompletionType.mixed && nestedCnt <= 1) {
            how = CompletionType.complete;
        }

        URI lra = new URI(client.target(TestPortProvider.generateURL("/base/test/start")).request().get(String.class));
        Response response = resourcePath
                .queryParam("nestedCnt", nestedCnt)
                .request()
                .header(LRA_HTTP_CONTEXT_HEADER, lra)
                .put(Entity.text(""));

        // the response is a comma separated list of URIs (parent, children)
        String lraStr = response.readEntity(String.class);
        assertNotNull("expecting a LRA string returned from " + resourcePath.getUri(), lraStr);

        URI[] uris = Arrays.stream(lraStr.split(",")).map(s -> {
            try {
                return new URI(s);
            } catch (URISyntaxException e) {
                fail(e.getMessage());
                return null;
            }
        }).toArray(URI[]::new);

        // check that the multiLevelNestedActivity method returned the mandatory LRA followed by any nested LRAs
        assertEquals("multiLevelNestedActivity: step 1 (the test call went to " + resourcePath.getUri() + ")",
                nestedCnt + 1, uris.length);
        // first element should be the mandatory LRA
        assertEquals("multiLevelNestedActivity: step 2 (the test call went to " + resourcePath.getUri() + ")",
                lra, uris[0]);

        // and the mandatory lra seen by the multiLevelNestedActivity method
        assertFalse("multiLevelNestedActivity: top level LRA should be active (path called " + resourcePath.getUri() + ")",
                isFinished(uris[0]));

        // check that all nested activities were told to complete
        assertEquals("multiLevelNestedActivity: step 3 (called test path " +
                resourcePath.getUri() + ")", nestedCnt, completeCount.get());
        assertEquals("multiLevelNestedActivity: step 4 (called test path " +
                resourcePath.getUri() + ")", 0, compensateCount.get());

        // close the LRA
        if (how == CompletionType.compensate) {
            lraClient.cancelLRA(lra);

            // validate that the top level and nested LRAs are gone
            assertAllFinished(uris);
            /*
             * the test starts LRA1 calls a @Mandatory method multiLevelNestedActivity which enlists in LRA1
             * multiLevelNestedActivity then calls an @Nested method which starts L2 and enlists another participant
             * when the method returns the nested participant is completed (ie completed count is incremented)
             * Canceling L1 should then compensate the L1 enlistment (ie compensate count is incremented)
             * which will then tell L2 to compensate (ie the compensate count is incremented again)
             */
            // each nested participant should have completed (the +nestedCnt)
            assertEquals("multiLevelNestedActivity: step 7 (called test path " +
                    resourcePath.getUri() + ")", nestedCnt, completeCount.get());
            // each nested participant should have compensated. The top level enlistment should have compensated (the +1)
            assertEquals("multiLevelNestedActivity: step 8 (called test path " +
                    resourcePath.getUri() + ")", nestedCnt + 1, compensateCount.get());
        } else if (how == CompletionType.complete) {
            lraClient.closeLRA(lra);

            // validate that the top level and nested LRAs are gone
            assertAllFinished(uris);

            // each nested participant and the top level participant should have completed (nestedCnt + 1) at least once
            assertTrue("multiLevelNestedActivity: step 5a (called test path " +
                    resourcePath.getUri() + ")", completeCount.get() >= nestedCnt + 1);
            // each nested participant should have been told to forget
            assertEquals("multiLevelNestedActivity: step 5b (called test path " +
                    resourcePath.getUri() + ")", forgetCount.get(), nestedCnt);
            // and that neither were still not told to compensate
            assertEquals("multiLevelNestedActivity: step 6 (called test path " +
                    resourcePath.getUri() + ")", 0, compensateCount.get());
        } else {
            /*
             * The test is calling for a mixed outcome (a top level LRA L1 and nestedCnt nested LRAs (L2, L3, ...)::
             * L1 the mandatory call (PUT "lraresource/multiLevelNestedActivity") registers participant C1
             * the resource makes nestedCnt calls to "lraresource/nestedActivity" each of which create nested LRAs
             * L2, L3, ... each of which enlists a participant (C2, C3, ...) which are completed when the call returns
             * L2 is cancelled which causes C2 to compensate
             * L1 is closed which triggers the completion of C1
             *
             * To summarise:
             *
             * - C1 is completed
             * - C2 is completed and then compensated
             * - C3, ... are completed
             */

            // compensate the first nested LRA in the enlisted resource
            try (Response r = client.target(TestPortProvider.generateURL("/base/test/end"))
                    .queryParam("cancel", true)
                    .request()
                    .header(LRA_HTTP_CONTEXT_HEADER, uris[1])
                    .put(Entity.text(""))) {
                assertEquals("compensate the first nested LRA", 500, r.getStatus());
            }

            lraClient.closeLRA(lra); // should not complete any nested LRAs (since they have already completed via the interceptor)

            /*
             * Expect nestedCnt + 1 completions, 1 for the top level and one for each nested LRA
             * (NB the first nested LRA is completed and compensated)
             * Note that the top level complete should not call complete again on the nested LRA
             */
            assertEquals("multiLevelNestedActivity: step 10 (called test path " +
                    resourcePath.getUri() + ")", nestedCnt + 1, completeCount.get());
            /*
             * The test is calling for a mixed outcome:
             * - the top level LRA was closed
             * - one of the nested LRAs was compensated the rest should have been completed
             */
            // there should be just 1 compensation (the first nested LRA)
            assertEquals("multiLevelNestedActivity: step 9 (called test path " +
                    resourcePath.getUri() + ")", 1, compensateCount.get());
        }

        // verify that the coordinator does not return any LRAs
        // ie assert lraClient.getAllLRAs().isEmpty() but for clarity check each one
        List<LRAData> lras = lraClient.getAllLRAs();
        LRAData parentData = new LRAData();
        parentData.setLraId(lra);
        assertFalse("parent LRA should not have been returned", lras.contains(parentData));

        for (URI uri : uris) {
            LRAData nestedData = new LRAData();
            nestedData.setLraId(uri);
            assertFalse("child LRA should not have been returned", lras.contains(nestedData));
        }
    }

    // validate that the top level and nested LRAs are gone
    private void assertAllFinished(URI[] uris) {
        assertTrue(uris.length != 0);
        IntStream.rangeClosed(0, uris.length - 1).forEach(i -> assertTrue(
                String.format("multiLevelNestedActivity: %s LRA still active",
                        (i == 0 ? "top level" : "nested")),
                isFinished(uris[i])));
    }

    @Test
    public void testGrandparentContext() {
        // start a hierarchy of three LRAs
        URI grandParent = lraClient.startLRA("NestedParticipantIT#testGrandparentContext grandparent");
        URI parent = lraClient.startLRA("NestedParticipantIT#testGrandparentContext parent"); // child of grandParent
        URI child = lraClient.startLRA("NestedParticipantIT#testGrandparentContext child"); // child of parent

        lraClient.closeLRA(grandParent); // should close everything in the hierarchy

        // nothing should be associated with the calling thread
        // and verify they are all closed
        assertStatus("grandparent", grandParent, null, LRAStatus.Closed);
        assertStatus("parent", parent, null, LRAStatus.Closed);
        assertStatus("child", child, null, LRAStatus.Closed);
    }

    @Test
    public void testParentLRAContext() {
        // start a hierarchy of three LRAs
        URI grandParent = lraClient.startLRA("NestedParticipantIT#testParentLRAContext grandparent");
        URI parent = lraClient.startLRA("NestedParticipantIT#testParentLRAContext parent"); // child of grandParent
        URI child = lraClient.startLRA("NestedParticipantIT#testParentLRAContext child"); // child of parent
        lraClient.closeLRA(parent); // should close everything in the hierarchy
        // verify the parent and child are closed
        assertStatus("grandparent", grandParent, null, LRAStatus.Active);
        assertStatus("parent", parent, null, LRAStatus.Closed);
        assertStatus("child", child, null, LRAStatus.Closed);
        lraClient.closeLRA(grandParent); // should close everything in the hierarchy
    }

    @Test
    public void testNestedLRAContext() {
        // start a hierarchy of three LRAs
        URI grandParent = lraClient.startLRA("NestedParticipantIT#testNestedLRAContext grandparent");
        URI parent = lraClient.startLRA("NestedParticipantIT#testParentLRAContext parent"); // child of grandParent
        URI child = lraClient.startLRA("NestedParticipantIT#testParentLRAContext child"); // child of parent
        lraClient.closeLRA(child); // should close everything in the hierarchy
        // verify they only the child is closed
        assertStatus("grandparent", grandParent, null, LRAStatus.Active);
        assertStatus("parent", parent, null, LRAStatus.Active);
        assertStatus("child", child, null, LRAStatus.Closed);
        lraClient.closeLRA(grandParent); // should close everything in the hierarchy
    }

    @Test
    public void testChild() {
        URI parentId = lraClient.startLRA("parent");
        URI childId = lraClient.startLRA(parentId, "child", 0L, ChronoUnit.SECONDS);

        lraClient.closeLRA(parentId);

        assertTrue("parent did not finish", isFinished(parentId));
        assertTrue("child did not finish", isFinished(childId));

        try {
            LRAStatus cStatus = lraClient.getStatus(childId);
            assertEquals("child did not close: " + cStatus, LRAStatus.Closed, cStatus);
        } catch (NotFoundException ignore) {
            // must have been cleaned up by the coordinator after it closed
        }

        try {
            LRAStatus pStatus = lraClient.getStatus(parentId);
            assertEquals("parent did not close: " + pStatus, LRAStatus.Closed, pStatus);
        } catch (NotFoundException ignore) {
            // must have been cleaned up by the coordinator after it closed
        }
    }

    @Test
    public void testClose() {
        runLRA(false);
    }

    @Test
    public void testCancel() {
        runLRA(true);
    }

    @Test
    public void testLoadSharing() {
        URI prev = null;

        for (int i = 0; i < 3; i++) {
            URI lra = lraClient.startLRA(Integer.toString(i));

            lraClient.closeLRA(lra);

            if (prev != null) {
                assertNotEquals(prev.getPort(), lra.getPort());
            }
            prev = lra;
        }
    }

    @Test
    public void testTimeout() throws URISyntaxException {
        int compensations = compensateCount.get();
        String lraId = client
                .target(TestPortProvider.generateURL("/base/test/time-limit"))
                .request()
                .get(String.class);
        assertEquals(compensations + 1, compensateCount.get());
        LRAStatus status = getStatus(new URI(lraId));
        assertTrue("LRA should have cancelled", status == null || status == LRAStatus.Cancelled);
    }

    @Test
    @BMRules(rules = {
            // a rule to abort an LRA when a participant is being enlisted
            @BMRule(name = "Rendezvous doEnlistParticipant", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "enlistParticipant", targetLocation = "ENTRY", helper = "io.narayana.lra.coordinator.domain.model.BytemanHelper", action = "abortLRA($0)")
    })
    public void testTimeoutWhileJoining() throws URISyntaxException {
        String target = TestPortProvider.generateURL("/base/test/timeout-while-joining");
        int compensations = compensateCount.get();

        try (Response response = client.target(target)
                .request()
                .get()) {
            assertEquals("expected HTTP 410 Gone", 410, response.getStatus());

            // assert that the business method was not invoked
            // the filter should detect that enlisting with the failed and respond with message code 25025
            String methodResponse = response.readEntity(String.class);

            assertNotEquals("business method should not have been called",
                    TIMEOUT_BEFORE_JOIN_BUSINESS_DATA, methodResponse);

            // LRA025025 is the generic error code but can we get more specific
            assertTrue("Expected LRA025025 but was " + methodResponse, methodResponse.startsWith("LRA025025"));
        }

        assertEquals("participant should not have been enlisted", compensations, compensateCount.get());
    }

    @Test
    public void testTimeOutWithNoParticipants() {
        URI lraId = lraClient.startLRA(null, testName.getMethodName(), 100L, ChronoUnit.MILLIS);

        // a) wait for the time limit to be reached
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // b) verify that the coordinator either removed it from its cache or it cancelled the LRA
        LRAStatus status = getStatus(lraId);
        assertTrue("LRA should have cancelled but it's in state " + status,
                status == null || status == LRAStatus.Cancelled);
        try {
            lraClient.cancelLRA(lraId);
            fail("should not be able to cancel a timed out LRA");
        } catch (WebApplicationException ignore) {
        }
    }

    @Test
    public void testLRAListener() throws InterruptedException, URISyntaxException {
        String businessMethodName = "/base/lra-listener/do-in-LRA"; // the one that creates the LRA
        String afterCheckMethodName = "/base/lra-listener/check-after"; // the one that reports the no of notifications
        URI lraId;

        // invoke a business method that should start and end an LRA
        try (Response r = client.target(TestPortProvider.generateURL(businessMethodName)).request().get()) {
            assertEquals("testLRAListener: business method call failed", 200, r.getStatus());
            lraId = new URI(r.readEntity(String.class)); // remember the LRA so that it's status can be verified
        }

        // verify that the AfterLRA annotated method ("/lra-listener/after") keeps getting called until it returns 200
        try (Response r = client.target(TestPortProvider.generateURL(afterCheckMethodName))
                .request().get()) {
            assertEquals("testLRAListener: check-after method call failed", 200, r.getStatus());

            Integer notificationsBeforeRecovery = r.readEntity(Integer.class);
            assertTrue("Expected at least one AfterLRA notifications", notificationsBeforeRecovery > 0);

            // verify that the coordinator still regards the LRA as finished even though there are still listeners
            LRAStatus status = getStatus(lraId);
            assertEquals("LRA should be in the closed state, not " + status, LRAStatus.Closed, status);

            // trigger a recovery scan so that the coordinator redelivers the listener notification which can take
            // a few seconds (maybe put this in a routine so that other tests can use it)
            try (Response r2 = client.target(recoveryPath).request().get()) {
                assertEquals("testLRAListener: trigger recovery method call failed", 200, r.getStatus());
                r2.getEntity(); // read the response stream, ignore the result (but we could check to see if contains lraId)
            }

            // check that the listener was notified again during the recovery scan
            try (Response r3 = client.target(TestPortProvider.generateURL(afterCheckMethodName)).request().get()) {
                assertEquals("testLRAListener: check-after method call failed", 200, r3.getStatus());
                int notificationsAfterRecovery = r3.readEntity(Integer.class);
                assertTrue("Expected the recovery scan to produce extra AfterLRA listener notifications",
                        notificationsAfterRecovery > notificationsBeforeRecovery);
            }

            // the AfterLRA notification handler during recovery ("/lra-listener/after")
            // should have returned status 200, verify that the LRA is gone

            try {
                status = lraClient.getStatus(lraId);

                fail("LRA should have gone but it is in state " + status);
            } catch (NotFoundException ignore) {
                // success the LRA is gone as expected
            } catch (WebApplicationException e) {
                assertEquals("status of LRA unavailable: " + e.getMessage(),
                        NOT_FOUND.getStatusCode(), e.getResponse().getStatus());
            }
        }
    }

    // fault injection tests

    @Test
    @BMScripts(scripts = {
            @BMScript("scripts/transition-active-failure")
    })
    public void testTransitionToActivateFailure() throws IOException, URISyntaxException {
        try {
            client.target(TestPortProvider.generateURL("/base/test/start")).request().get(String.class);
            fail("expected ServiceUnavailableException on startLRA");
        } catch (ServiceUnavailableException e) {
            // expected since the byteman script fails the attempt to write to the store
            Response response = e.getResponse();
            assertNotNull("missing response object", response);
            // the response should be 503
            assertEquals(503, response.getStatus());
            assertTrue(response.hasEntity());
            String message = response.readEntity(String.class);
            assertTrue(message.contains("LRA025032"));
        }

        // verify that nothing was written to the store
        try {
            assertEquals("LRA record should not have been created", 0, countRecords());
        } catch (ObjectStoreException e) {
            fail("Unable to read the store: " + e.getMessage());
        }
    }

    @Test
    @BMRules(rules = {
            // a rule to fail store writes when an LRA participant is being enlisted
            @BMRule(name = "fail deactivate during enlist", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "enlistParticipant", targetLocation = "AFTER INVOKE deactivate", action = "$! = false;")
    })
    public void testEnlistFailure() throws IOException, URISyntaxException {
        try {
            Object res = client.target(TestPortProvider.generateURL("/base/test/start-end")).request().get(Object.class);
            fail("should have thrown ServiceUnavailableException but returned " + res);
        } catch (WebApplicationException e) {
            assertEquals("Unexpected response code",
                    Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), e.getResponse().getStatus());
            String reason = e.getResponse().readEntity(String.class);
            assertTrue("response does not contain LRA025032",
                    reason.contains("LRA025032")); // LRA025032 means deactivate failed
        }
    }

    @Test
    @BMRules(rules = {
            // a rule to fail store writes when an LRA participant is being enlisted
            @BMRule(name = "fail deactivate during close", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(LRAStatus, boolean)", targetLocation = "AFTER INVOKE deactivate", action = "$! = false;")
    })
    public void testCloseFailure() {
        testEndFailure(true);
    }

    @Test
    @BMRules(rules = {
            // a rule to fail store writes when an LRA participant is being enlisted
            @BMRule(name = "fail deactivate during close", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(LRAStatus, boolean)", targetLocation = "AFTER INVOKE deactivate", action = "$! = false;")
    })
    public void testCancelFailure() {
        testEndFailure(true);
    }

    public void testEndFailure(boolean closeLRA) {
        // start an LRA by calling the coordinator
        URI lraId = lraClient.startLRA(testName.getMethodName());

        // enlist a participant via the participant filter (ie ServerLRAFilter)
        try (Response r = client.target(TestPortProvider.generateURL("/base/participant1/continue"))
                .request()
                .header(LRA_HTTP_CONTEXT_HEADER, lraId).get()) { // invoke the service, setting the LRA context
            assertEquals(200, r.getStatus());
        }

        try {
            // close/cancel the LRA by calling the coordinator
            if (closeLRA) {
                lraClient.closeLRA(lraId);
            } else {
                lraClient.cancelLRA(lraId);
            }
        } catch (WebApplicationException e) {
            Response response = e.getResponse();
            assertNotNull("missing response object", response);
            // the response should be 503
            assertEquals(503, response.getStatus());
            assertTrue(response.hasEntity());
            String message = response.readEntity(String.class);
            assertTrue(message.contains("LRA025032"));
        }
    }

    /*
     * Service A - Timeout 500 ms Service B (Type.MANDATORY)
     * Service A calls Service B after it has waited 1 sec. Service A returns http Status from the call to Service B.
     *
     * The test calls A and verifies if return is status 412 (precondition failed) or 410 (gone) since LRA is not
     * active when Service B endpoint is called.
     */
    // TODO this method is slow because of the sleeps - change it to use byteman
    @Test
    public void testTimeLimitWithPreConditionFailed() {
        try (Response response = client.target(TestPortProvider.generateURL("/base/test/time-limit2")).request().get()) {

            assertThat("Expected 412 or 410 response", response.getStatus(),
                    Matchers.anyOf(Matchers.is(Response.Status.PRECONDITION_FAILED.getStatusCode()),
                            Matchers.is(Response.Status.GONE.getStatusCode())));
        }
    }

    private void runLRA(boolean cancel) {
        URI parentId = lraClient.startLRA("parent");
        URI childId = lraClient.startLRA(parentId, "child", 0L, ChronoUnit.SECONDS);

        // enlist a participant with the child
        enlistParticipant(childId.toASCIIString().split("\\?")[0]);
        // enlist a participant with the parent
        enlistParticipant(parentId.toASCIIString().split("\\?")[0]);

        if (cancel)
            lraClient.cancelLRA(parentId);
        else
            lraClient.closeLRA(parentId);

        assertEquals("parent and child should both have finished",
                2, cancel ? compensateCount.get() : completeCount.get());

        LRAStatus pStatus = getStatus(parentId);
        LRAStatus cStatus = getStatus(childId);

        assertTrue("parent LRA finished in wrong state",
                pStatus == null || pStatus == (cancel ? LRAStatus.Cancelled : LRAStatus.Closed));
        assertTrue("child LRA finished in wrong state",
                cStatus == null || cStatus == (cancel ? LRAStatus.Cancelled : LRAStatus.Closed));
    }

    private void enlistParticipant(String lraUid) {
        try (Response response = client.target(lraUid).request().put(Entity.text(getCompensatorLinkHeader()))) {
            assertEquals("Unexpected status: " + response.readEntity(String.class),
                    200, response.getStatus());
            String recoveryId = response.getHeaderString(LRA_HTTP_RECOVERY_HEADER);
            assertNotNull("recovery id was null", recoveryId);
        }
    }

    LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        } catch (WebApplicationException e) {
            assertNotNull(e);
            assertEquals(e.getResponse().getStatus(), NOT_FOUND.getStatusCode());
            return null;
        }
    }

    private boolean isFinished(URI lra) {
        LRAStatus status;

        try {
            status = getStatus(lra);
        } catch (NotFoundException e) {
            status = null; // most likely finished already
        }

        return status == null
                || status == LRAStatus.Closed || status == LRAStatus.Cancelled
                || status == LRAStatus.FailedToClose || status == LRAStatus.FailedToCancel;
    }

    private void assertStatus(String message, URI lraId, LRAStatus... expectedValues) {
        try {
            LRAStatus status = getStatus(lraId);

            assertTrue(message + ": LRA status: " + status,
                    Arrays.stream(expectedValues).anyMatch(s -> s == status));
        } catch (NotFoundException e) {
            List<LRAStatus> values = Arrays.asList(expectedValues);
            // if the LRA finished then the coordinator is free to clean up so NotFoundException is valid
            assertTrue(values.contains(LRAStatus.Closed)
                    || values.contains(LRAStatus.Cancelled)); // what about FailedToXXX
        }
    }

    private void assertStatus(String lraId, LRAStatus expected, boolean nullValid) {
        try {
            LRAStatus status = getStatus(new URI(lraId));

            assertTrue("unexpected null LRA status", status != null || nullValid);

            assertTrue("Expected status " + expected + " but state was " + status,
                    status == null || status == expected);
        } catch (URISyntaxException e) {
            fail(String.format("%s: %s", testName.getMethodName(), e.getMessage()));
        }
    }

    private String getCompensatorLinkHeader() {
        String prefix = TestPortProvider.generateURL("/base/test");

        return String.join(",",
                makeLink(prefix, "forget"),
                makeLink(prefix, "after"),
                makeLink(prefix, "complete"),
                makeLink(prefix, "compensate"));
    }

    private static String makeLink(String uriPrefix, String key) {
        return Link.fromUri(String.format("%s/%s", uriPrefix, key))
                .title(key + " URI")
                .rel(key)
                .type(MediaType.TEXT_PLAIN)
                .build().toString();
    }

}
