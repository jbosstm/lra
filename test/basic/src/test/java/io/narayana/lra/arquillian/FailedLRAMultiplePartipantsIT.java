/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static io.narayana.lra.LRAConstants.RECOVERY_COORDINATOR_PATH_NAME;
import static io.narayana.lra.arquillian.resource.LRAMultipleParticipant1Initiator.BASE_URL_PARAM;
import static io.narayana.lra.arquillian.resource.LRAMultipleParticipant1Initiator.LRA_PARTICIPANT_PATH;
import static io.narayana.lra.arquillian.resource.LRAMultipleParticipant1Initiator.TRANSACTIONAL_START_PATH;
import static jakarta.ws.rs.core.Response.Status.NO_CONTENT;
import static org.eclipse.microprofile.lra.annotation.LRAStatus.FailedToCancel;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.arquillian.resource.LRAMultipleParticipant1Initiator;
import io.narayana.lra.arquillian.resource.LRAMultipleParticipant2;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * There is a spec requirement to report failed LRAs but the spec only requires that a failure message is reported
 * (not how it is reported). Failure records are vital pieces of data needed to aid failure tracking and analysis.
 * <p>
 * The Narayana implementation allows failed LRAs to be directly queried. The following tests validate that the
 * correct failure records are kept until explicitly removed.
 */
public class FailedLRAMultiplePartipantsIT extends TestBase {
    private static final Logger log = Logger.getLogger(FailedLRAMultiplePartipantsIT.class);

    @ArquillianResource
    public URL baseURL;

    public String testName;

    public void before(TestInfo testInfo) {
        testName = testInfo.getDisplayName();
        log.info("Running test " + testName);
    }

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(FailedLRAMultiplePartipantsIT.class.getSimpleName(),
                LRAMultipleParticipant1Initiator.class, LRAMultipleParticipant2.class);
    }

    static String getRecoveryUrl(URI lraId) {
        return LRAConstants.getLRACoordinatorUrl(lraId) + "/" + RECOVERY_COORDINATOR_PATH_NAME;
    }

    /**
     * test that a failure record is created when a participant (with status reporting) fails to compensate
     */
    @Test
    public void testWithStatusCompensateFailed() {
        URI lraId = invokeInTransaction(LRA_PARTICIPANT_PATH, TRANSACTIONAL_START_PATH, 500);

        lrasToAfterFinish.add(lraId);

        String status1 = getLraEndStatus(
                LRAMultipleParticipant1Initiator.LRA_PARTICIPANT_PATH,
                LRAMultipleParticipant1Initiator.LRA_END_STATUS,
                lraId);

        assertEquals("FailedToCancel", status1);

        log.infov("Received status 1 {0} (should be FailedToCancel)", status1);

        String status2 = getLraEndStatus(
                LRAMultipleParticipant2.LRA_PARTICIPANT_PATH,
                LRAMultipleParticipant2.LRA_END_STATUS,
                lraId);

        assertEquals("FailedToCancel", status2);

        log.infov("Received status 2 {0} (should be FailedToCancel)", status2);

        if (!validateStateAndRemove(lraId, FailedToCancel)) {
            fail("lra not in failed list (should have been)");
        }
    }

    private String getLraEndStatus(String resourcePrefix, String resourcePath, URI lraId) {
        for (int i = 0; i < 10; i++) {
            try (Response response = client.target(baseURL.toURI())
                    .path(resourcePrefix)
                    .path(resourcePath)
                    .queryParam(LRA_HTTP_CONTEXT_HEADER, lraId)
                    .request()
                    .get()) {

                Assertions.assertTrue(response.hasEntity(),
                        "Expecting a non empty body in response from " + resourcePrefix + "/" + resourcePath);

                if (response.getStatus() != 202) {
                    return response.readEntity(String.class);
                }

                log.infov("AfterLRA not yet called. Waiting {0}/10", i);
                Thread.sleep(1000);

            } catch (URISyntaxException e) {
                throw new IllegalStateException("response cannot be converted to URI: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    private URI invokeInTransaction(String resourcePrefix, String resourcePath, int expectedStatus) {
        try (Response response = client.target(baseURL.toURI())
                .path(resourcePrefix)
                .path(resourcePath)
                .queryParam(BASE_URL_PARAM, baseURL)
                .request()
                .get()) {

            Assertions.assertTrue(response.hasEntity(),
                    "Expecting a non empty body in response from " + resourcePrefix + "/" + resourcePath);

            String entity = response.readEntity(String.class);

            Assertions.assertEquals(
                    expectedStatus, response.getStatus(),
                    "response from " + resourcePrefix + "/" + resourcePath + " was " + entity);

            return new URI(entity);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("response cannot be converted to URI: " + e.getMessage());
        }
    }

    /**
     * Validate whether a given LRA is in a particular state.
     * Also validate that the corresponding record is removed.
     *
     * @param lra the LRA whose state is to be validated
     * @param state the state that the target LRA should be in
     * @return true if the LRA is in the target state and that its log was successfully removed
     */
    private boolean validateStateAndRemove(URI lra, LRAStatus state) {
        for (JsonValue failedLRA : getFailedRecords(lra)) {

            String lraId = failedLRA.asJsonObject().getString("lraId")
                    .replaceAll("\\\\", "");

            if (lraId.contains(lra.toASCIIString())) {
                if (failedLRA.asJsonObject().getString("status").equals(state.name())) {

                    // Remove the failed LRA
                    Assertions.assertEquals(NO_CONTENT.getStatusCode(),
                            removeFailedLRA(lra),
                            "Could not remove log");

                    return true;
                }
            }
        }

        return false;
    }

    // Look up LRAs that are in a failed state (ie FailedToCancel or FailedToClose)
    private JsonArray getFailedRecords(URI lra) {
        String recoveryUrl = getRecoveryUrl(lra);

        try (Response response = client.target(recoveryUrl).path("failed").request().get()) {
            Assertions.assertTrue(response.hasEntity(), "Missing response body when querying for failed LRAs");
            String failedLRAs = response.readEntity(String.class);

            return Json.createReader(new StringReader(failedLRAs)).readArray();
        }
    }

    // Ask the recovery coordinator to delete its log for an LRA
    private int removeFailedLRA(URI lra) {
        String recoveryUrl = getRecoveryUrl(lra);
        String txId = URLEncoder.encode(lra.toASCIIString(), StandardCharsets.UTF_8);

        return removeFailedLRA(recoveryUrl, txId);
    }

    // Ask the recovery coordinator to delete its log for an LRA
    private int removeFailedLRA(String recoveryUrl, String lra) {
        try (Response response = client.target(recoveryUrl).path(lra).request().delete()) {
            return response.getStatus();
        }
    }

    @BeforeEach
    public void setup(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
    }
}
