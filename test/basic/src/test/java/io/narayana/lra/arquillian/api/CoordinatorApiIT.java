/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.api;

import static io.narayana.lra.LRAConstants.API_VERSION_1_0;
import static io.narayana.lra.LRAConstants.API_VERSION_1_1;
import static io.narayana.lra.LRAConstants.API_VERSION_1_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.hamcrest.core.IsCollectionContaining.hasItems;
import static org.hamcrest.core.IsNot.not;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.Deployer;
import io.narayana.lra.arquillian.TestBase;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <p>
 * REST API tests for Narayana LRA Coordinator.<br/>
 * Each test case corresponds by name with the method in the {@link io.narayana.lra.coordinator.api.Coordinator}.
 * The test case verifies the expected API responses.
 * It verifies the status code, the return data, data format, headers etc.
 * </p>
 * <p>
 * The test may be annotated with {@link ValidTestVersions}.
 * That way we can say the test will be executed only for the defined versions.
 * The execution for a version not defined in the annotation is skipped.
 * <p>
 * When a new API version is added - when the new version
 * <ul>
 * <li>does <b>not</b> change the functionality of the API endpoint
 * then nothing is needed - the test will be executed with the new version as well</li>
 * <li>changes the functionality of the API endpoint
 * then the test needs to be limited for execution with preceding API versions
 * and the creation of a new test to document the new behaviour should be considered</li>
 * </ul>
 * </p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
public class CoordinatorApiIT extends TestBase {
    private static final Logger log = Logger.getLogger(CoordinatorApiIT.class);

    // not reusing the LRAConstants as the API tests need to be independent to functionality code changes
    static final String LRA_API_VERSION_HEADER_NAME = "Narayana-LRA-API-version";
    static final String RECOVERY_HEADER_NAME = "Long-Running-Action-Recovery";
    static final String STATUS_PARAM_NAME = "Status";
    static final String CLIENT_ID_PARAM_NAME = "ClientID";
    static final String TIME_LIMIT_PARAM_NAME = "TimeLimit";
    static final String PARENT_LRA_PARAM_NAME = "ParentLRA";

    public static Iterable<?> parameters() {
        return Arrays.asList(LRAConstants.NARAYANA_LRA_API_SUPPORTED_VERSIONS);
    }

    public String version;

    @ArquillianResource
    private URL baseURL;

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(CoordinatorApiIT.class.getSimpleName(), NoopParticipant.class);
    }

    private String testName;

    @BeforeEach
    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    /**
     * GET - /
     * To gets all active LRAs.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void getAllLRAs(String version) {
        initCoordinatorApiIT(version);
        // be aware of risk of non-monotonic java time,
        // i.e. https://www.javaadvent.com/2019/12/measuring-time-from-java-to-kernel-and-back.html
        long beforeTime = Instant.now().toEpochMilli();

        String clientId1 = testName + "_OK_1";
        String clientId2 = testName + "_OK_2";
        URI lraId1 = lraClient.startLRA(clientId1);
        URI lraId2 = lraClient.startLRA(lraId1, clientId2, 0L, null);
        lrasToAfterFinish.add(lraId1); // lraId2 is nested and will be closed in regard to lraId1

        List<LRAData> data;
        try (Response response = client.target(coordinatorUrl)
                .request().header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected that the call succeeds, GET/200.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Provided API header, expected that one is returned");
            data = response.readEntity(new GenericType<>() {
            });
        }

        Optional<LRAData> lraTopOptional = data.stream().filter(record -> record.getLraId().equals(lraId1)).findFirst();
        Assertions.assertTrue(lraTopOptional.isPresent(),
                "Expected to find the top-level LRA id " + lraId1 + " from REST get all call");
        LRAData lraTop = lraTopOptional.get();
        Optional<LRAData> lraNestedOptional = data.stream().filter(record -> record.getLraId().equals(lraId2)).findFirst();
        Assertions.assertTrue(lraNestedOptional.isPresent(),
                "Expected to find the nested LRA id " + lraId2 + " from REST get all call");
        LRAData lraNested = lraNestedOptional.get();

        Assertions.assertEquals(LRAStatus.Active, lraTop.getStatus(), "Expected top-level LRA '" + lraTop + "'  being active");
        Assertions.assertEquals(Status.NO_CONTENT.getStatusCode(), lraTop.getHttpStatus(),
                "Expected top-level LRA '" + lraTop + "'  being active, HTTP status 204.");
        Assertions.assertFalse(lraTop.isRecovering(), "Expected top-level LRA '" + lraTop + "' not being recovering");
        Assertions.assertTrue(lraTop.isTopLevel(), "Expected top-level LRA '" + lraTop + "' to be top level");
        assertThat("Expected the start time of top-level LRA '" + lraTop + "' is after the test start time",
                beforeTime, lessThan(lraTop.getStartTime()));

        Assertions.assertEquals(LRAStatus.Active, lraNested.getStatus(),
                "Expected nested LRA '" + lraNested + "'  being active");
        Assertions.assertEquals(Status.NO_CONTENT.getStatusCode(), lraNested.getHttpStatus(),
                "Expected nested LRA '" + lraNested + "'  being active, HTTP status 204.");
        Assertions.assertFalse(lraNested.isRecovering(), "Expected nested LRA '" + lraNested + "' not being recovering");
        Assertions.assertFalse(lraNested.isTopLevel(), "Expected nested LRA '" + lraNested + "' to be nested");
        assertThat("Expected the start time of nested LRA '" + lraNested + "' is after the test start time",
                beforeTime, lessThan(lraNested.getStartTime()));
    }

    /**
     * GET - /?Status=Active
     * To gets active LRAs with status.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void getAllLRAsStatusFilter(String version) {
        initCoordinatorApiIT(version);
        String clientId1 = testName + "_1";
        String clientId2 = testName + "_2";
        URI lraId1 = lraClient.startLRA(clientId1);
        URI lraId2 = lraClient.startLRA(lraId1, clientId2, 0L, null);
        lrasToAfterFinish.add(lraId1);
        lraClient.closeLRA(lraId2);

        try (Response response = client.target(coordinatorUrl).request()
                .header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected that the call succeeds, GET/200.");
            List<LRAData> data = response.readEntity(new GenericType<>() {
            });
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            Collection<URI> returnedLraIds = data.stream().map(LRAData::getLraId).collect(Collectors.toList());
            assertThat("Expected the coordinator returns the first started and second closed LRA",
                    returnedLraIds, hasItems(lraId1, lraId2));
        }
        try (Response response = client.target(coordinatorUrl)
                .queryParam(STATUS_PARAM_NAME, "Active").request().get()) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected that the call succeeds, GET/200.");
            List<LRAData> data = response.readEntity(new GenericType<>() {
            });
            Collection<URI> returnedLraIds = data.stream().map(LRAData::getLraId).collect(Collectors.toList());
            assertThat("Expected the coordinator returns the first started top-level LRA",
                    returnedLraIds, hasItem(lraId1));
            assertThat("Expected the coordinator filtered out the non-active nested LRA",
                    returnedLraIds, not(hasItem(lraId2)));
        }
    }

    /**
     * GET - /?Status=NonExistingStatus
     * Asking for LRAs with status while providing a wrong status.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void getAllLRAsFailedStatus(String version) {
        initCoordinatorApiIT(version);
        String nonExistingStatusValue = "NotExistingStatusValue";
        try (Response response = client.target(coordinatorUrl)
                .queryParam(STATUS_PARAM_NAME, nonExistingStatusValue).request()
                .header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                    "Expected that the call fails on wrong status, GET/500.");
            assertThat("Expected the failure to contain the wrong status value",
                    response.readEntity(String.class), containsString(nonExistingStatusValue));
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
        }
    }

    /**
     * GET - /{lraId}/status
     * Finding a status of a started LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void getLRAStatus(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl).path(encodedLraId).path("status")
                .request().header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.OK.getStatusCode(),
                    response.getStatus(),
                    "Expected that the get status call succeeds, GET/200.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            Assertions.assertEquals("Active", response.readEntity(String.class), "Expected the returned LRA status is Active");
        }
    }

    /**
     * GET - /{lraId}/status
     * Finding a status of a non-existing LRA or wrong LRA id.
     */
    public void getLRAStatusFailed() {
        String nonExistingLRAId = "http://localhost:1234/Non-Existing-LRA-id";
        String nonExistingLRAIdEncodedForUrl = URLEncoder.encode("http://localhost:1234/Non-Existing-LRA-id",
                StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl).path(nonExistingLRAIdEncodedForUrl).path("status")
                .request().header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "LRA ID " + nonExistingLRAIdEncodedForUrl + " was expected not being found, GET/404.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected the failure message to contain the wrong LRA id",
                    response.readEntity(String.class), containsString(nonExistingLRAId));
        }

        String nonExistingLRAWrongUrlFormat = "Non-Existing-LRA-id";
        try (Response response = client.target(coordinatorUrl).path(nonExistingLRAWrongUrlFormat).path("status").request()
                .get()) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "LRA id " + nonExistingLRAWrongUrlFormat + " was expected not being found , GET/404.");
            assertThat("Expected the failure message to contain the wrong LRA id",
                    response.readEntity(String.class),
                    containsString(lraClient.getCoordinatorUrl() + "/" + nonExistingLRAWrongUrlFormat));
        }
    }

    /**
     * GET - /{lraId}
     * Obtaining info of a started LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void getLRAInfo(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl).path(encodedLraId)
                .request().header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.OK.getStatusCode(),
                    response.getStatus(),
                    "Expected that the get status call succeeds, GET/200.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            LRAData data = response.readEntity(new GenericType<>() {
            });
            Assertions.assertEquals(lraId, data.getLraId(), "Expected the returned LRA to be the one that was started by test");
            Assertions.assertEquals(LRAStatus.Active, data.getStatus(), "Expected the returned LRA being Active");
            Assertions.assertTrue(data.isTopLevel(), "Expected the returned LRA is top-level");
            Assertions.assertEquals(Status.NO_CONTENT.getStatusCode(), data.getHttpStatus(),
                    "Expected the returned LRA get HTTP status as active, HTTP status 204.");
        }
    }

    /**
     * GET - /{lraId}
     * Obtaining info of a non-existing LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void getLRAInfoNotExisting(String version) {
        initCoordinatorApiIT(version);
        String nonExistingLRA = "Non-Existing-LRA-id";
        try (Response response = client.target(coordinatorUrl).path(nonExistingLRA).request()
                .header(LRA_API_VERSION_HEADER_NAME, version).get()) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected that the call fails on LRA not found, GET/404.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected the failure message to contain the wrong LRA id",
                    response.readEntity(String.class), containsString(nonExistingLRA));
        }
    }

    /**
     * POST - /start?TimeLimit=...&ClientID=...&ParentLRA=...
     * PUT - /{lraId}/close
     * Starting and closing an LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void startCloseLRA(String version) {
        initCoordinatorApiIT(version);
        URI lraId1, lraId2;

        try (Response response = client.target(coordinatorUrl)
                .path("start")
                .queryParam(CLIENT_ID_PARAM_NAME, testName + "_1")
                .queryParam(TIME_LIMIT_PARAM_NAME, "-42") // negative time limit is permitted by spec
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .post(null)) {
            Assertions.assertEquals(Status.CREATED.getStatusCode(), response.getStatus(),
                    "Creating top-level LRA should be successful, POST/201 is expected.");
            lraId1 = URI.create(response.readEntity(String.class));
            Assertions.assertNotNull(lraId1, "Expected non null LRA id to be returned from start call");
            lrasToAfterFinish.add(lraId1);

            URI lraIdFromLocationHeader = URI.create(response.getHeaderString(HttpHeaders.LOCATION));
            Assertions.assertEquals(lraId1, lraIdFromLocationHeader,
                    "Expected the LOCATION header containing the started top-level LRA id");
            // context header is returned strangely to client, some investigation will be needed
            // URI lraIdFromLRAContextHeader = URI.create(response.getHeaderString(LRA.LRA_HTTP_CONTEXT_HEADER));
            // Assert.assertEquals("Expecting the LRA context header configures the same LRA id as entity content on starting top-level LRA",
            //        lraId1, lraIdFromLRAContextHeader);
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expecting to get the same API version as used for the request on top-level LRA start");
        }

        String encodedLraId1 = URLEncoder.encode(lraId1.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path("start")
                .queryParam(CLIENT_ID_PARAM_NAME, testName + "_2")
                .queryParam(PARENT_LRA_PARAM_NAME, encodedLraId1)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .post(null)) {
            Assertions.assertEquals(Status.CREATED.getStatusCode(), response.getStatus(),
                    "Creating nested LRA should be successful, POST/201 is expected.");
            lraId2 = URI.create(response.readEntity(String.class));
            Assertions.assertNotNull(lraId2, "Expected non null nested LRA id being returned in the response body");

            // the nested LRA id is in format <nested LRA id>?ParentLRA=<parent LRA id>
            URI lraIdFromLocationHeader = URI.create(response.getHeaderString(HttpHeaders.LOCATION));
            Assertions.assertEquals(lraId2, lraIdFromLocationHeader,
                    "Expected the LOCATION header containing the started nested LRA id");
            // context header is returned strangely to client, some investigation will be needed
            // String lraContextHeader = response.getHeaderString(LRA.LRA_HTTP_CONTEXT_HEADER);
            // the context header is in format <parent LRA id>,<nested LRA id>?ParentLRA=<parent LRA id>
            // MatcherAssert.assertThat("Expected the nested LRA context header gives the parent LRA id at first",
            //        lraContextHeader, startsWith(lraId1.toASCIIString()));
            // MatcherAssert.assertThat("Expected the nested LRA context header provides LRA id of started nested LRA",
            //        lraContextHeader, containsString("," + lraId2.toASCIIString()));
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expecting to get the same API version as used for the request on nested LRA start");
        }

        Collection<URI> returnedLraIds = lraClient.getAllLRAs().stream().map(LRAData::getLraId).collect(Collectors.toList());
        assertThat("Expected the coordinator knows about the top-level LRA", returnedLraIds, hasItem(lraId1));
        assertThat("Expected the coordinator knows about the nested LRA", returnedLraIds, hasItem(lraId2));

        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId1 + "/close")
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(null)) {
            lrasToAfterFinish.clear(); // we've closed the LRA manually here, skipping the @After
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Closing top-level LRA should be successful, PUT/200 is expected.");
            Assertions.assertEquals(LRAStatus.Closed.name(), response.readEntity(String.class),
                    "Closing top-level LRA should return the right status.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expecting to get the same API version as used for the request to close top-level LRA");
        }

        Collection<LRAData> activeLRAsAfterClosing = lraClient.getAllLRAs().stream()
                .filter(data -> data.getLraId().equals(lraId1) || data.getLraId().equals(lraId2))
                .filter(data -> data.getStatus() != LRAStatus.Closing && data.getStatus() != LRAStatus.Closed)
                .collect(Collectors.toList());
        assertThat("Expecting the started LRAs are no more active after closing the top-level one",
                activeLRAsAfterClosing, emptyCollectionOf(LRAData.class));
    }

    /**
     * POST - /start?ClientID=...
     * PUT - /{lraId}/cancel
     * Starting and canceling an LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void startCancelLRA(String version) {
        initCoordinatorApiIT(version);
        URI lraId;
        try (Response response = client.target(coordinatorUrl)
                .path("start")
                .queryParam(CLIENT_ID_PARAM_NAME, testName)
                .request()
                .post(null)) {
            Assertions.assertEquals(Status.CREATED.getStatusCode(), response.getStatus(),
                    "Creating top-level LRA should be successful, POST/201 is expected.");
            lraId = URI.create(response.readEntity(String.class));
            Assertions.assertNotNull(lraId, "Expected non null LRA id to be returned from start call");
            lrasToAfterFinish.add(lraId);
            Assertions.assertTrue(
                    response.getHeaders().containsKey(LRA_API_VERSION_HEADER_NAME),
                    "API version header is expected on response despite no API version header was provided on request");
        }

        Collection<URI> returnedLraIds = lraClient.getAllLRAs().stream().map(LRAData::getLraId).collect(Collectors.toList());
        assertThat("Expected the coordinator knows about the LRA", returnedLraIds, hasItem(lraId));
        try (Response response = client.target(coordinatorUrl)
                .path(URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8) + "/cancel")
                .request()
                .put(null)) {
            lrasToAfterFinish.clear(); // we've closed the LRA manually just now, skipping the @After
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Closing LRA should be successful, PUT/200 is expected.");
            Assertions.assertEquals(LRAStatus.Cancelled.name(), response.readEntity(String.class),
                    "Canceling top-level LRA should return the right status.");
            Assertions.assertTrue(
                    response.getHeaders().containsKey(LRA_API_VERSION_HEADER_NAME),
                    "API version header is expected on response despite no API header parameter was provided on request");
        }

        Collection<LRAData> activeLRAsAfterClosing = lraClient.getAllLRAs().stream()
                .filter(data -> data.getLraId().equals(lraId)).collect(Collectors.toList());
        assertThat("Expecting the started LRA is no more active after closing it",
                activeLRAsAfterClosing, emptyCollectionOf(LRAData.class));
    }

    /**
     * POST - /start?ClientId=...&ParentLRA=...
     * Starting a nested LRA with a non-existing parent.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void startLRANotExistingParentLRA(String version) {
        initCoordinatorApiIT(version);
        String notExistingParentLRA = "not-existing-parent-lra-id";
        try (Response response = client.target(coordinatorUrl)
                .path("start")
                .queryParam(CLIENT_ID_PARAM_NAME, testName)
                .queryParam(PARENT_LRA_PARAM_NAME, notExistingParentLRA)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .post(null)) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected failure on non-existing parent LRA, POST/404 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String errorMsg = response.readEntity(String.class);
            assertThat("Expected error message to contain the not found parent LRA id",
                    errorMsg, containsString(notExistingParentLRA));
        }
    }

    /**
     * PUT - /{lraId}/close
     * Closing a non-existing LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void closeNotExistingLRA(String version) {
        initCoordinatorApiIT(version);
        String notExistingLRAid = "not-existing-lra-id";
        try (Response response = client.target(coordinatorUrl)
                .path(notExistingLRAid)
                .path("close")
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(null)) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected failure on non-existing LRA id, PUT/404 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String errorMsg = response.readEntity(String.class);
            assertThat("Expected error message to contain the not found LRA id",
                    errorMsg, containsString(notExistingLRAid));
        }
    }

    /**
     * PUT - /{lraId}/cancel
     * Canceling a non-existing LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void cancelNotExistingLRA(String version) {
        initCoordinatorApiIT(version);
        String notExistingLRAid = "not-existing-lra-id";
        try (Response response = client.target(coordinatorUrl)
                .path(notExistingLRAid)
                .path("cancel")
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(null)) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected failure on non-existing LRA id, PUT/404 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String errorMsg = response.readEntity(String.class);
            assertThat("Expected error message to contain the not found LRA id",
                    errorMsg, containsString(notExistingLRAid));
        }
    }

    /**
     * PUT - /renew?TimeLimit=
     * Renewing the time limit of the started LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void renewTimeLimit(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        Optional<LRAData> data = lraClient.getAllLRAs().stream().filter(l -> l.getLraId().equals(lraId)).findFirst();
        Assertions.assertTrue(data.isPresent(), "Expected the started LRA will be retrieved by LRA client get");
        Assertions.assertEquals(0L, data.get().getFinishTime(), "Expected not defined finish time");

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .path("renew")
                .queryParam(TIME_LIMIT_PARAM_NAME, Integer.MAX_VALUE)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(null)) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected time limit request to succeed, PUT/200 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected the found LRA id is returned",
                    response.readEntity(String.class), containsString(lraId.toString()));
        }

        data = lraClient.getAllLRAs().stream().filter(l -> l.getLraId().equals(lraId)).findFirst();
        Assertions.assertTrue(data.isPresent(), "Expected the started LRA will be retrieved by LRA client get");
        assertThat("Expected finish time to not be 0 as time limit was defined",
                data.get().getFinishTime(), greaterThan(0L));
    }

    /**
     * PUT - /renew?TimeLimit=
     * Renewing the time limit of a non-existing LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void renewTimeLimitNotExistingLRA(String version) {
        initCoordinatorApiIT(version);
        String notExistingLRAid = "not-existing-lra-id";
        try (Response response = client.target(coordinatorUrl)
                .path(notExistingLRAid)
                .path("renew")
                .queryParam(TIME_LIMIT_PARAM_NAME, Integer.MAX_VALUE)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(null)) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected time limit request to fail for non existing LRA id, PUT/404");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String errorMsg = response.readEntity(String.class);
            assertThat("Expected error message to contain the not found LRA id",
                    errorMsg, containsString(notExistingLRAid));
        }
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via entity body.
     */
    @ParameterizedTest(name = "#{index}, version: {0}")
    @ValueSource(strings = { API_VERSION_1_0, API_VERSION_1_1 }) // the recovery url was unusable in previous versions
    public void joinLRAWithBody(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                // the request body should correspond to a valid compensator or be empty
                .put(Entity.text(""))) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected joining LRA succeeded, PUT/200 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            URI recoveryUrlLocation = response.getLocation();
            Assertions.assertEquals(recoveryUrlBody, recoveryHeaderUrlMessage,
                    "Expecting returned body and recovery header have got the same content");
            Assertions.assertEquals(recoveryUrlBody, recoveryUrlLocation.toString(),
                    "Expecting returned body and location have got the same content");
            assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
            assertThat("Expected returned message contains the LRA id",
                    recoveryUrlBody, containsString(encodedLraId));
        }
    }

    @ParameterizedTest(name = "#{index}, version: {0}")
    @ValueSource(strings = { API_VERSION_1_2 })
    // the recovery url is usable versions after API_VERSION_1_1
    // Remark if the API version is incremented then the new value for the version will need adding to annotation
    public void joinLRAWithBodyWithCorrectRecoveryHeader(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);
        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8); // must be valid

        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                // the request body should correspond to a valid compensator or be empty
                .put(Entity.text(""))) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected joining LRA succeeded, PUT/200 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            URI recoveryUrlLocation = response.getLocation();
            Assertions.assertEquals(recoveryUrlBody, recoveryHeaderUrlMessage,
                    "Expecting returned body and recovery header have got the same content");
            Assertions.assertEquals(recoveryUrlBody, recoveryUrlLocation.toString(),
                    "Expecting returned body and location have got the same content");
            assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
            assertThat("Expected returned message contains the LRA id",
                    recoveryUrlBody, containsString(LRAConstants.getLRAUid(lraId)));
        }
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via link header.
     */
    @ParameterizedTest(name = "#{index}, version: {0}")
    @ValueSource(strings = { API_VERSION_1_0, API_VERSION_1_1 })
    public void joinLRAWithLinkSimple(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .header("Link", "http://compensator.url:8080")
                .put(null)) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected joining LRA succeeded, PUT/200 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            URI recoveryUrlLocation = response.getLocation();
            Assertions.assertEquals(recoveryUrlBody, recoveryHeaderUrlMessage,
                    "Expecting returned body and recovery header have got the same content");
            Assertions.assertEquals(recoveryUrlBody, recoveryUrlLocation.toString(),
                    "Expecting returned body and location have got the same content");
            assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
            assertThat("Expected returned message contains the LRA id",
                    recoveryUrlBody, containsString(encodedLraId));
        }
    }

    @ParameterizedTest(name = "#{index}, version: {0}")
    @ValueSource(strings = { API_VERSION_1_2 })
    public void joinLRAWithLinkSimpleWithCorrectRecoveryHeader(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .header("Link", "http://compensator.url:8080")
                .put(null)) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected joining LRA succeeded, PUT/200 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            URI recoveryUrlLocation = response.getLocation();
            Assertions.assertEquals(recoveryUrlBody, recoveryHeaderUrlMessage,
                    "Expecting returned body and recovery header have got the same content");
            Assertions.assertEquals(recoveryUrlBody, recoveryUrlLocation.toString(),
                    "Expecting returned body and location have got the same content");
            assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
            assertThat("Expected returned message contains the LRA id",
                    recoveryUrlBody, containsString(LRAConstants.getLRAUid(lraId)));
        }
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via link header with link rel specified.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void joinLRAWithLinkCompensate(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        Link link = Link.fromUri(getNoopURL()).rel("compensate").build();
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header("Link", link.toString())
                .put(null)) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected joining LRA succeeded, PUT/200 is expected.");
            Assertions.assertTrue(
                    response.getHeaders().containsKey(LRA_API_VERSION_HEADER_NAME),
                    "API version header is expected on response despite no API version header was provided on request");
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            Assertions.assertEquals(recoveryUrlBody, recoveryHeaderUrlMessage,
                    "Expecting returned body and recovery header have got the same content");
            assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    recoveryUrlBody, containsString("lra-coordinator/recovery"));
        }
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via link header with link after specified.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void joinLRAWithLinkAfter(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        Link afterLink = Link.fromUri(getNoopURL()).rel("after").build();
        Link unknownLink = Link.fromUri("http://unknow.url:8080").rel("unknown").build();
        String linkList = afterLink.toString() + "," + unknownLink.toString();
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header("Link", linkList)
                .put(null)) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected joining LRA succeeded, PUT/200 is expected.");
            String recoveryHeaderUrlMessage = response.getHeaderString(RECOVERY_HEADER_NAME);
            String recoveryUrlBody = response.readEntity(String.class);
            Assertions.assertEquals(recoveryUrlBody, recoveryHeaderUrlMessage,
                    "Expecting returned body and recovery header have got the same content");
            assertThat("Expected returned message contains the sub-path of LRA recovery URL",
                    URLDecoder.decode(recoveryUrlBody, StandardCharsets.UTF_8), containsString("lra-coordinator/recovery"));
        }
    }

    private URI getNoopURL() {
        return URI.create(String.format("%s%s", baseURL.toExternalForm(), NoopParticipant.NOOP_PARTICIPANT_PATH));
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via link header with wrong link format.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void joinLRAIncorrectLinkFormat(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);
        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header("Link", "<link>;rel=myrel;<wrong>")
                .put(null)) {
            Assertions.assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus(),
                    "Expected the join failing, PUT/500 is expected.");
        }
    }

    /**
     * PUT - /{lraId}
     * Joining a non-existing LRA.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void joinLRAUnknownLRA(String version) {
        initCoordinatorApiIT(version);
        String notExistingLRAid = "not-existing-lra-id";
        try (Response response = client.target(coordinatorUrl)
                .path(notExistingLRAid)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                // the request body should correspond to a valid compensator or be empty
                .put(Entity.text(""))) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected the join failing on unknown LRA id, PUT/404 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected error message to contain the LRA id where enlist failed",
                    response.readEntity(String.class), containsString(notExistingLRAid));
        }
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via entity body of a wrong format.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void joinLRAWrongCompensatorData(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);
        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(Entity.text("this-is-not-a-valid-url::::"))) {
            Assertions.assertEquals(Status.PRECONDITION_FAILED.getStatusCode(), response.getStatus(),
                    "Expected the join failing on wrong compensator data format, PUT/412 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected error message to contain the LRA id where enlist failed",
                    response.readEntity(String.class), containsString(lraId.toString()));
        }
    }

    /**
     * PUT - /{lraId}
     * Joining an LRA participant via link header missing required rel items.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void joinLRAWithLinkNotEnoughData(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);

        String encodedLraId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        Link link = Link.fromUri(getNoopURL()).rel("complete").build();
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLraId)
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .header("Link", link.toString())
                .put(null)) {
            Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                    "Expected the joining fails as no compensate in link, PUT/400 is expected.");
            String errorMsg = response.readEntity(String.class);
            assertThat("Expected error message to contain the LRA id where enlist failed",
                    errorMsg, containsString(lraId.toString()));
        }
    }

    /**
     * PUT - /{lraId}/remove
     * Leaving an LRA as participant.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void leaveLRA(String version) {
        initCoordinatorApiIT(version);
        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);
        URI recoveryUri = lraClient.joinLRA(lraId, 0L, URI.create("http://localhost:8080"), new StringBuilder());

        String encodedLRAId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl)
                .path(encodedLRAId)
                .path("remove")
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(Entity.text(recoveryUri.toString()))) {
            Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                    "Expected leaving the LRA to succeed, PUT/200 is expected.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            Assertions.assertFalse(response.hasEntity(), "Expecting 'remove' API call returns no entity body");
        }

        try (Response response = client.target(coordinatorUrl)
                .path(encodedLRAId)
                .path("remove")
                .request()
                .header(LRA_API_VERSION_HEADER_NAME, version)
                .put(Entity.text(recoveryUri.toString()))) {
            Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                    "Expected leaving the LRA to fail as it was removed just before, PUT/400 is expected.");
            assertThat("Expected the failure message to contain the non existing participant id",
                    response.readEntity(String.class), containsString(recoveryUri.toASCIIString()));
        }
    }

    /**
     * PUT - /{lraId}/remove
     * Leaving a non-existing LRA as participant.
     */
    @MethodSource("parameters")
    @ParameterizedTest(name = "#{index}, version: {0}")
    public void leaveLRANonExistingFailure(String version) {
        initCoordinatorApiIT(version);
        String nonExistingLRAId = "http://localhost:1234/Non-Existing-LRA-id";
        String encodedNonExistingLRAId = URLEncoder.encode(nonExistingLRAId, StandardCharsets.UTF_8);
        try (Response response = client.target(coordinatorUrl).path(encodedNonExistingLRAId).path("remove").request()
                .header(LRA_API_VERSION_HEADER_NAME, version).put(Entity.text("nothing"))) {
            Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "Expected that the call finds not found of " + encodedNonExistingLRAId + ", PUT/404.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected the failure message to contain the wrong LRA id",
                    response.readEntity(String.class), containsString(nonExistingLRAId));
        }

        URI lraId = lraClient.startLRA(testName);
        lrasToAfterFinish.add(lraId);
        String encodedLRAId = URLEncoder.encode(lraId.toString(), StandardCharsets.UTF_8);
        String nonExistingParticipantUrl = "http://localhost:1234/Non-Existing-participant-LRA";
        try (Response response = client.target(coordinatorUrl).path(encodedLRAId).path("remove").request()
                .header(LRA_API_VERSION_HEADER_NAME, version).put(Entity.text(nonExistingParticipantUrl))) {
            Assertions.assertEquals(
                    Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                    "Expected that the call fails on LRA participant " + nonExistingParticipantUrl + " not found , PUT/400.");
            Assertions.assertEquals(version, response.getHeaderString(LRA_API_VERSION_HEADER_NAME),
                    "Expected API header to be returned with the version provided in request");
            assertThat("Expected the failure message to contain the wrong participant id",
                    response.readEntity(String.class), containsString(nonExistingParticipantUrl));
        }
    }

    public void initCoordinatorApiIT(String version) {
        this.version = version;
    }

}
