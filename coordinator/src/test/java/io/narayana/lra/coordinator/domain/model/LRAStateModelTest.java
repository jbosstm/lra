/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COMPENSATE;
import static io.narayana.lra.LRAConstants.COMPLETE;
import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static io.narayana.lra.LRAConstants.FORGET;
import static io.narayana.lra.LRAConstants.NESTED_COORDINATOR_PATH_NAME;
import static io.narayana.lra.LRAConstants.STATUS;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_RECOVERY_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import io.narayana.lra.provider.ParticipantStatusOctetStreamProvider;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.jboss.byteman.contrib.bmunit.WithByteman;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Tests for LRA and participant state model transitions as defined in the
 * MicroProfile LRA Specification.
 *
 * LRA state model:
 * Active -> Closing -> Closed
 * Active -> Closing -> FailedToClose
 * Active -> Cancelling -> Cancelled
 * Active -> Cancelling -> FailedToCancel
 *
 * Participant state model (nested LRA as participant):
 * Active -> Completing -> Completed
 * Active -> Completing -> Completed -> Compensating
 * Active -> Completing -> FailedToComplete
 * Active -> Compensating -> Compensated
 * Active -> Compensating -> FailedToCompensate
 */
@WithByteman
public class LRAStateModelTest extends LRATestBase {

    private NarayanaLRAClient lraClient;
    private Client client;
    private String coordinatorPath;
    int[] ports = { 8081, 8082 };
    public String testName;

    @ApplicationPath("base")
    public static class LRAParticipantApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Participant.class);
            classes.add(FailingParticipant.class);
            classes.add(ServerLRAFilter.class);
            classes.add(ParticipantStatusOctetStreamProvider.class);
            classes.add(BytemanHelper.class);
            return classes;
        }
    }

    @ApplicationPath("/")
    public static class LRACoordinatorApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            return classes;
        }
    }

    /**
     * A participant whose complete and compensate endpoints always return HTTP 500
     * with the appropriate FailedToComplete/FailedToCompensate body, causing the
     * coordinator to record a participant failure.
     */
    @Path("/failing-test")
    public static class FailingParticipant {
        @PUT
        @Path("/complete")
        @Complete
        public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ParticipantStatus.FailedToComplete.name())
                    .build();
        }

        @PUT
        @Path("/compensate")
        @Compensate
        public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ParticipantStatus.FailedToCompensate.name())
                    .build();
        }
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
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
                LRALogger.logger.infof("before test %s: could not start server %s", testName, e.getMessage());
            }

            sb.append(String.format("http://%s:%d/%s%s",
                    host, ports[i], COORDINATOR_PATH_NAME, i + 1 < ports.length ? "," : ""));
        }

        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY, sb.toString());

        lraClient = new NarayanaLRAClient();
        client = ClientBuilder.newClient();
        coordinatorPath = TestPortProvider.generateURL('/' + COORDINATOR_PATH_NAME);

        for (UndertowJaxrsServer server : servers) {
            server.deploy(LRACoordinatorApp.class);
            server.deployOldStyle(LRAParticipantApp.class);
        }

        LRARecoveryModule.getService();

        if (lraClient.getCurrent() != null) {
            LRALogger.logger.warnf("before test %s: current thread should not be associated with any LRAs", testName);
            lraClient.clearCurrent(true);
        }
    }

    @AfterEach
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
        }
    }

    // -- Helpers --

    private LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == NOT_FOUND.getStatusCode()) {
                return null;
            }
            throw e;
        }
    }

    private void enlistFailingParticipant(URI lraId) {
        String lraUid = lraId.toASCIIString().split("\\?")[0];
        String prefix = TestPortProvider.generateURL("/base/failing-test");
        String linkHeader = String.join(",",
                makeLink(prefix, COMPLETE),
                makeLink(prefix, COMPENSATE));

        try (Response response = client.target(lraUid).request().put(Entity.text(linkHeader))) {
            assertEquals(200, response.getStatus(),
                    "Unexpected status enlisting failing participant: " + response.readEntity(String.class));
            String recoveryId = response.getHeaderString(LRA_HTTP_RECOVERY_HEADER);
            assertNotNull(recoveryId, "recovery id was null for failing participant");
        }
    }

    private static String makeLink(String uriPrefix, String key) {
        return Link.fromUri(String.format("%s/%s", uriPrefix, key))
                .title(key + " URI")
                .rel(key)
                .type(MediaType.TEXT_PLAIN)
                .build().toString();
    }

    // ===================================================================
    // LRA State Model Tests
    // ===================================================================

    /**
     * LRA state model: Active -> Closing -> Closed
     *
     * A Byteman rule pauses the coordinator thread after updateState sets the status
     * to Closing, allowing the test to observe the transient state.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at Closing transient state", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Closing", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"closing-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"closing-proceed\", 10)")
    })
    public void testClosingTransientState() throws InterruptedException {
        BytemanHelper.createRendezvous("closing-reached");
        BytemanHelper.createRendezvous("closing-proceed");

        URI lraId = lraClient.startLRA(testName);
        assertEquals(LRAStatus.Active, getStatus(lraId), "LRA should start in Active state");

        Thread closeThread = new Thread(() -> lraClient.closeLRA(lraId));
        closeThread.start();

        assertTrue(BytemanHelper.awaitRendezvous("closing-reached", 10),
                "Timed out waiting for LRA to reach Closing state");

        assertEquals(LRAStatus.Closing, getStatus(lraId),
                "LRA must be in Closing transient state during close processing");

        BytemanHelper.signalRendezvous("closing-proceed");
        closeThread.join(10_000);

        LRAStatus finalStatus = getStatus(lraId);
        assertTrue(finalStatus == null || finalStatus == LRAStatus.Closed,
                "LRA should be Closed or removed after close completes");

        BytemanHelper.removeRendezvous("closing-reached");
        BytemanHelper.removeRendezvous("closing-proceed");
    }

    /**
     * LRA state model: Active -> Cancelling -> Cancelled
     *
     * A Byteman rule pauses the coordinator thread after updateState sets the status
     * to Cancelling, allowing the test to observe the transient state.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at Cancelling transient state", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelling", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"cancelling-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"cancelling-proceed\", 10)")
    })
    public void testCancellingTransientState() throws InterruptedException {
        BytemanHelper.createRendezvous("cancelling-reached");
        BytemanHelper.createRendezvous("cancelling-proceed");

        URI lraId = lraClient.startLRA(testName);
        assertEquals(LRAStatus.Active, getStatus(lraId), "LRA should start in Active state");

        Thread cancelThread = new Thread(() -> lraClient.cancelLRA(lraId));
        cancelThread.start();

        assertTrue(BytemanHelper.awaitRendezvous("cancelling-reached", 10),
                "Timed out waiting for LRA to reach Cancelling state");

        assertEquals(LRAStatus.Cancelling, getStatus(lraId),
                "LRA must be in Cancelling transient state during cancel processing");

        BytemanHelper.signalRendezvous("cancelling-proceed");
        cancelThread.join(10_000);

        LRAStatus finalStatus = getStatus(lraId);
        assertTrue(finalStatus == null || finalStatus == LRAStatus.Cancelled,
                "LRA should be Cancelled or removed after cancel completes");

        BytemanHelper.removeRendezvous("cancelling-reached");
        BytemanHelper.removeRendezvous("cancelling-proceed");
    }

    /**
     * LRA state model: Active -> Closing -> FailedToClose
     *
     * A failing participant is enlisted whose complete endpoint returns HTTP 500
     * with FailedToComplete body. Two Byteman rules are used: the first pauses at
     * the Closing transient state, the second pauses at the FailedToClose state
     * (to observe it before the coordinator cleans it up).
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at Closing transient state for failure", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Closing", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"closing-fail-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"closing-fail-proceed\", 10)"),
            @BMRule(name = "pause at FailedToClose state", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.FailedToClose", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"failed-to-close-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"failed-to-close-proceed\", 10)")
    })
    public void testFailedToCloseTransientState() throws InterruptedException {
        BytemanHelper.createRendezvous("closing-fail-reached");
        BytemanHelper.createRendezvous("closing-fail-proceed");
        BytemanHelper.createRendezvous("failed-to-close-reached");
        BytemanHelper.createRendezvous("failed-to-close-proceed");

        URI lraId = lraClient.startLRA(testName);
        enlistFailingParticipant(lraId);
        assertEquals(LRAStatus.Active, getStatus(lraId), "LRA should start in Active state");

        Thread closeThread = new Thread(() -> lraClient.closeLRA(lraId));
        closeThread.start();

        // Verify Closing transient state
        assertTrue(BytemanHelper.awaitRendezvous("closing-fail-reached", 10),
                "Timed out waiting for LRA to reach Closing state");
        assertEquals(LRAStatus.Closing, getStatus(lraId),
                "LRA must be in Closing transient state during close processing");
        BytemanHelper.signalRendezvous("closing-fail-proceed");

        // Verify FailedToClose state before coordinator cleans it up
        assertTrue(BytemanHelper.awaitRendezvous("failed-to-close-reached", 10),
                "Timed out waiting for LRA to reach FailedToClose state");
        assertEquals(LRAStatus.FailedToClose, getStatus(lraId),
                "LRA should be FailedToClose when a participant fails to complete");
        BytemanHelper.signalRendezvous("failed-to-close-proceed");

        closeThread.join(10_000);

        BytemanHelper.removeRendezvous("closing-fail-reached");
        BytemanHelper.removeRendezvous("closing-fail-proceed");
        BytemanHelper.removeRendezvous("failed-to-close-reached");
        BytemanHelper.removeRendezvous("failed-to-close-proceed");
    }

    /**
     * LRA state model: Active -> Cancelling -> FailedToCancel
     *
     * A failing participant is enlisted whose compensate endpoint returns HTTP 500
     * with FailedToCompensate body. Two Byteman rules are used: the first pauses at
     * the Cancelling transient state, the second pauses at the FailedToCancel state
     * (to observe it before the coordinator cleans it up).
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at Cancelling transient state for failure", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelling", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"cancelling-fail-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"cancelling-fail-proceed\", 10)"),
            @BMRule(name = "pause at FailedToCancel state", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.FailedToCancel", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"failed-to-cancel-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"failed-to-cancel-proceed\", 10)")
    })
    public void testFailedToCancelTransientState() throws InterruptedException {
        BytemanHelper.createRendezvous("cancelling-fail-reached");
        BytemanHelper.createRendezvous("cancelling-fail-proceed");
        BytemanHelper.createRendezvous("failed-to-cancel-reached");
        BytemanHelper.createRendezvous("failed-to-cancel-proceed");

        URI lraId = lraClient.startLRA(testName);
        enlistFailingParticipant(lraId);
        assertEquals(LRAStatus.Active, getStatus(lraId), "LRA should start in Active state");

        Thread cancelThread = new Thread(() -> lraClient.cancelLRA(lraId));
        cancelThread.start();

        // Verify Cancelling transient state
        assertTrue(BytemanHelper.awaitRendezvous("cancelling-fail-reached", 10),
                "Timed out waiting for LRA to reach Cancelling state");
        assertEquals(LRAStatus.Cancelling, getStatus(lraId),
                "LRA must be in Cancelling transient state during cancel processing");
        BytemanHelper.signalRendezvous("cancelling-fail-proceed");

        // Verify FailedToCancel state before coordinator cleans it up
        assertTrue(BytemanHelper.awaitRendezvous("failed-to-cancel-reached", 10),
                "Timed out waiting for LRA to reach FailedToCancel state");
        assertEquals(LRAStatus.FailedToCancel, getStatus(lraId),
                "LRA should be FailedToCancel when a participant fails to compensate");
        BytemanHelper.signalRendezvous("failed-to-cancel-proceed");

        cancelThread.join(10_000);

        BytemanHelper.removeRendezvous("cancelling-fail-reached");
        BytemanHelper.removeRendezvous("cancelling-fail-proceed");
        BytemanHelper.removeRendezvous("failed-to-cancel-reached");
        BytemanHelper.removeRendezvous("failed-to-cancel-proceed");
    }

    // ===================================================================
    // Participant State Model Tests (nested LRA as participant)
    // ===================================================================

    /**
     * Participant state model: Active -> Completing -> Completed
     *
     * A Byteman rule pauses the coordinator when the nested LRA reaches the Closing
     * transient state (which maps to Completing participant status), allowing the
     * test to observe the transient participant status.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Closing state during complete", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Closing && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"complete-closing-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"complete-closing-proceed\", 10)")
    })
    public void testParticipantStateModelClosePath() throws InterruptedException {
        BytemanHelper.createRendezvous("complete-closing-reached");
        BytemanHelper.createRendezvous("complete-closing-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        ParticipantStatus initialStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Active, initialStatus,
                "A newly enlisted participant must be in Active state");

        // Capture the return value from completeNestedLRA since the LRA may be
        // cleaned up before we can query its status
        AtomicReference<ParticipantStatus> completeResult = new AtomicReference<>();
        Thread completeThread = new Thread(() -> completeResult.set(lraClient.completeNestedLRA(childId)));
        completeThread.start();

        assertTrue(BytemanHelper.awaitRendezvous("complete-closing-reached", 10),
                "Timed out waiting for nested LRA to reach Closing state");

        ParticipantStatus transientStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Completing, transientStatus,
                "Nested LRA participant must be in Completing transient state during close");

        BytemanHelper.signalRendezvous("complete-closing-proceed");
        completeThread.join(10_000);

        assertEquals(ParticipantStatus.Completed, completeResult.get(),
                "After complete the participant must be in Completed state");

        BytemanHelper.removeRendezvous("complete-closing-reached");
        BytemanHelper.removeRendezvous("complete-closing-proceed");
        lraClient.closeLRA(parentId);
    }

    /**
     * Participant state model: Active -> Compensating -> Compensated
     *
     * A Byteman rule pauses the coordinator when the nested LRA reaches the
     * Cancelling transient state (which maps to Compensating participant status).
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Cancelling state during compensate", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelling && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"compensate-cancelling-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"compensate-cancelling-proceed\", 10)")
    })
    public void testParticipantStateModelCancelPath() throws InterruptedException {
        BytemanHelper.createRendezvous("compensate-cancelling-reached");
        BytemanHelper.createRendezvous("compensate-cancelling-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        ParticipantStatus initialStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Active, initialStatus,
                "A newly enlisted participant must be in Active state");

        AtomicReference<ParticipantStatus> compensateResult = new AtomicReference<>();
        Thread compensateThread = new Thread(() -> compensateResult.set(lraClient.compensateNestedLRA(childId)));
        compensateThread.start();

        assertTrue(BytemanHelper.awaitRendezvous("compensate-cancelling-reached", 10),
                "Timed out waiting for nested LRA to reach Cancelling state");

        ParticipantStatus transientStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Compensating, transientStatus,
                "Nested LRA participant must be in Compensating transient state during cancel");

        BytemanHelper.signalRendezvous("compensate-cancelling-proceed");
        compensateThread.join(10_000);

        assertEquals(ParticipantStatus.Compensated, compensateResult.get(),
                "After compensate the participant must be in Compensated state");

        BytemanHelper.removeRendezvous("compensate-cancelling-reached");
        BytemanHelper.removeRendezvous("compensate-cancelling-proceed");
        lraClient.cancelLRA(parentId);
    }

    /**
     * Participant state model: Active -> Completing -> Completed -> Compensating
     *
     * A nested LRA is completed (participant status Completed), then the parent is
     * cancelled which triggers compensation of the completed nested LRA. A Byteman
     * rule pauses the coordinator when the nested LRA reaches the Cancelling
     * transient state (Compensating participant status) to verify the transition
     * from Completed to Compensating.
     *
     **/
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Cancelling after completed", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelling && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"completed-to-compensating-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"completed-to-compensating-proceed\", 10)")
    })
    public void testParticipantCompletedToCompensating() throws InterruptedException {
        BytemanHelper.createRendezvous("completed-to-compensating-reached");
        BytemanHelper.createRendezvous("completed-to-compensating-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        // Complete the nested LRA - participant goes Active -> Completed
        ParticipantStatus completedStatus = lraClient.completeNestedLRA(childId);
        assertEquals(ParticipantStatus.Completed, completedStatus,
                "After complete the participant must be in Completed state");

        // Cancel the parent which triggers compensation of the completed child
        Thread cancelThread = new Thread(() -> lraClient.cancelLRA(parentId));
        cancelThread.start();

        // Wait for the child to reach the Cancelling (Compensating) transient state
        assertTrue(BytemanHelper.awaitRendezvous("completed-to-compensating-reached", 10),
                "Timed out waiting for completed nested LRA to reach Cancelling state");

        // Verify the child is in Compensating transient state
        ParticipantStatus transientStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Compensating, transientStatus,
                "Completed nested LRA participant must transition to Compensating when parent cancels");

        BytemanHelper.signalRendezvous("completed-to-compensating-proceed");
        cancelThread.join(10_000);

        BytemanHelper.removeRendezvous("completed-to-compensating-reached");
        BytemanHelper.removeRendezvous("completed-to-compensating-proceed");
        lraClient.clearCurrent(false);
    }

    /**
     * Participant state model: Active -> Completing -> FailedToComplete
     *
     * A failing participant is enlisted in the nested LRA. When the nested LRA is
     * completed, the failing participant causes the nested LRA to transition to
     * FailedToClose, which maps to the FailedToComplete participant status.
     *
     * A Byteman rule pauses at the Closing transient state so the test can observe
     * the Completing participant status. A second rule pauses at the FailedToClose
     * state to observe FailedToComplete before the coordinator cleans it up.
     *
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Closing for failure", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Closing && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"complete-fail-closing-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"complete-fail-closing-proceed\", 10)"),
            @BMRule(name = "pause at child FailedToClose", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.FailedToClose && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"child-failed-to-close-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"child-failed-to-close-proceed\", 10)")
    })
    public void testParticipantFailedToComplete() throws InterruptedException {
        BytemanHelper.createRendezvous("complete-fail-closing-reached");
        BytemanHelper.createRendezvous("complete-fail-closing-proceed");
        BytemanHelper.createRendezvous("child-failed-to-close-reached");
        BytemanHelper.createRendezvous("child-failed-to-close-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        // Enlist a failing participant in the nested LRA
        enlistFailingParticipant(childId);

        ParticipantStatus initialStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Active, initialStatus,
                "A newly enlisted participant must be in Active state");

        Thread completeThread = new Thread(() -> lraClient.completeNestedLRA(childId));
        completeThread.start();

        // Verify Completing transient state
        assertTrue(BytemanHelper.awaitRendezvous("complete-fail-closing-reached", 10),
                "Timed out waiting for nested LRA to reach Closing state");
        ParticipantStatus transientStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Completing, transientStatus,
                "Nested LRA participant must be in Completing transient state");
        BytemanHelper.signalRendezvous("complete-fail-closing-proceed");

        // Verify FailedToComplete state before cleanup
        assertTrue(BytemanHelper.awaitRendezvous("child-failed-to-close-reached", 10),
                "Timed out waiting for nested LRA to reach FailedToClose state");
        ParticipantStatus failedStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.FailedToComplete, failedStatus,
                "Nested LRA participant must be in FailedToComplete when its participant fails");
        BytemanHelper.signalRendezvous("child-failed-to-close-proceed");

        completeThread.join(10_000);

        BytemanHelper.removeRendezvous("complete-fail-closing-reached");
        BytemanHelper.removeRendezvous("complete-fail-closing-proceed");
        BytemanHelper.removeRendezvous("child-failed-to-close-reached");
        BytemanHelper.removeRendezvous("child-failed-to-close-proceed");
        lraClient.cancelLRA(parentId);
    }

    /**
     * Participant state model: Active -> Compensating -> FailedToCompensate
     *
     * A failing participant is enlisted in the nested LRA. When the nested LRA is
     * compensated, the failing participant causes the nested LRA to transition to
     * FailedToCancel, which maps to the FailedToCompensate participant status.
     *
     * A Byteman rule pauses at the Cancelling transient state so the test can
     * observe the Compensating participant status. A second rule pauses at the
     * FailedToCancel state to observe FailedToCompensate before cleanup.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Cancelling for failure", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelling && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"compensate-fail-cancelling-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"compensate-fail-cancelling-proceed\", 10)"),
            @BMRule(name = "pause at child FailedToCancel", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.FailedToCancel && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"child-failed-to-cancel-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"child-failed-to-cancel-proceed\", 10)")
    })
    public void testParticipantFailedToCompensate() throws InterruptedException {
        BytemanHelper.createRendezvous("compensate-fail-cancelling-reached");
        BytemanHelper.createRendezvous("compensate-fail-cancelling-proceed");
        BytemanHelper.createRendezvous("child-failed-to-cancel-reached");
        BytemanHelper.createRendezvous("child-failed-to-cancel-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        // Enlist a failing participant in the nested LRA
        enlistFailingParticipant(childId);

        ParticipantStatus initialStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Active, initialStatus,
                "A newly enlisted participant must be in Active state");

        Thread compensateThread = new Thread(() -> lraClient.compensateNestedLRA(childId));
        compensateThread.start();

        // Verify Compensating transient state
        assertTrue(BytemanHelper.awaitRendezvous("compensate-fail-cancelling-reached", 10),
                "Timed out waiting for nested LRA to reach Cancelling state");
        ParticipantStatus transientStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Compensating, transientStatus,
                "Nested LRA participant must be in Compensating transient state");
        BytemanHelper.signalRendezvous("compensate-fail-cancelling-proceed");

        // Verify FailedToCompensate state before cleanup
        assertTrue(BytemanHelper.awaitRendezvous("child-failed-to-cancel-reached", 10),
                "Timed out waiting for nested LRA to reach FailedToCancel state");
        ParticipantStatus failedStatus = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.FailedToCompensate, failedStatus,
                "Nested LRA participant must be in FailedToCompensate when its participant fails");
        BytemanHelper.signalRendezvous("child-failed-to-cancel-proceed");

        compensateThread.join(10_000);

        BytemanHelper.removeRendezvous("compensate-fail-cancelling-reached");
        BytemanHelper.removeRendezvous("compensate-fail-cancelling-proceed");
        BytemanHelper.removeRendezvous("child-failed-to-cancel-reached");
        BytemanHelper.removeRendezvous("child-failed-to-cancel-proceed");
        lraClient.cancelLRA(parentId);
    }

    // ===================================================================
    // Cascade Tests (parent close/cancel cascading to nested LRA)
    // ===================================================================

    /**
     * Validates that closing a parent LRA cascades to nested LRAs following the
     * participant state model: Active -> Closing -> Closed
     *
     * A Byteman rule pauses the coordinator when the nested (non-top-level)
     * LRA reaches the Closing transient state.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Closing state", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Closing && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"child-closing-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"child-closing-proceed\", 10)")
    })
    public void testParticipantStateModelParentCloseCascade() throws InterruptedException {
        BytemanHelper.createRendezvous("child-closing-reached");
        BytemanHelper.createRendezvous("child-closing-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        ParticipantStatus beforeClose = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Active, beforeClose,
                "Nested LRA participant must be Active before parent close");

        Thread closeThread = new Thread(() -> lraClient.closeLRA(parentId));
        closeThread.start();

        assertTrue(BytemanHelper.awaitRendezvous("child-closing-reached", 10),
                "Timed out waiting for child LRA to reach Closing state");

        LRAStatus childTransientStatus = getStatus(childId);
        assertEquals(LRAStatus.Closing, childTransientStatus,
                "Child LRA must be in Closing transient state during parent close cascade");

        BytemanHelper.signalRendezvous("child-closing-proceed");
        closeThread.join(10_000);

        LRAStatus childFinalStatus = getStatus(childId);
        if (childFinalStatus != null) {
            assertEquals(LRAStatus.Closed, childFinalStatus,
                    "After parent close, nested LRA should be Closed");
        }

        BytemanHelper.removeRendezvous("child-closing-reached");
        BytemanHelper.removeRendezvous("child-closing-proceed");
        lraClient.clearCurrent(false);
    }

    /**
     * Validates that cancelling a parent LRA cascades to nested LRAs following the
     * participant state model: Active -> Cancelling -> Cancelled
     *
     * A Byteman rule pauses the coordinator when the nested (non-top-level)
     * LRA reaches the Cancelling transient state.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "pause at child Cancelling state", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelling && !$0.isTopLevel()", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"child-cancelling-reached\");"
                    + "io.narayana.lra.coordinator.domain.model.BytemanHelper.awaitRendezvousBM(\"child-cancelling-proceed\", 10)")
    })
    public void testParticipantStateModelParentCancelCascade() throws InterruptedException {
        BytemanHelper.createRendezvous("child-cancelling-reached");
        BytemanHelper.createRendezvous("child-cancelling-proceed");

        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        ParticipantStatus beforeCancel = lraClient.getNestedLRAStatus(childId);
        assertEquals(ParticipantStatus.Active, beforeCancel,
                "Nested LRA participant must be Active before parent cancel");

        Thread cancelThread = new Thread(() -> lraClient.cancelLRA(parentId));
        cancelThread.start();

        assertTrue(BytemanHelper.awaitRendezvous("child-cancelling-reached", 10),
                "Timed out waiting for child LRA to reach Cancelling state");

        LRAStatus childTransientStatus = getStatus(childId);
        assertEquals(LRAStatus.Cancelling, childTransientStatus,
                "Child LRA must be in Cancelling transient state during parent cancel cascade");

        BytemanHelper.signalRendezvous("child-cancelling-proceed");
        cancelThread.join(10_000);

        LRAStatus childFinalStatus = getStatus(childId);
        if (childFinalStatus != null) {
            assertEquals(LRAStatus.Cancelled, childFinalStatus,
                    "After parent cancel, nested LRA should be Cancelled");
        }

        BytemanHelper.removeRendezvous("child-cancelling-reached");
        BytemanHelper.removeRendezvous("child-cancelling-proceed");
        lraClient.clearCurrent(false);
    }

    // ===================================================================
    // Mapping Consistency Tests
    // ===================================================================

    /**
     * Validates that the LRAStatus-to-ParticipantStatus mapping used when the
     * coordinator acts as a participant for nested LRAs is consistent with
     * MicroProfile LRA Specification.
     *
     * The mapping must be:
     * LRAStatus.Active -> ParticipantStatus.Active
     * LRAStatus.Closing -> ParticipantStatus.Completing
     * LRAStatus.Closed -> ParticipantStatus.Completed
     * LRAStatus.Cancelling -> ParticipantStatus.Compensating
     * LRAStatus.Cancelled -> ParticipantStatus.Compensated
     * LRAStatus.FailedToClose -> ParticipantStatus.FailedToComplete
     * LRAStatus.FailedToCancel -> ParticipantStatus.FailedToCompensate
     */
    @Test
    public void testNestedLRAStatusMappingConsistency() {
        // Verify Active -> Active mapping
        URI parentId1 = lraClient.startLRA(testName + "-parent1");
        URI childId1 = lraClient.startLRA(parentId1, testName + "-child1", 0L, ChronoUnit.SECONDS);

        LRAStatus childLRAStatus = lraClient.getStatus(childId1);
        ParticipantStatus childParticipantStatus = lraClient.getNestedLRAStatus(childId1);

        assertEquals(LRAStatus.Active, childLRAStatus,
                "Child LRA should be in Active LRAStatus");
        assertEquals(ParticipantStatus.Active, childParticipantStatus,
                "Active LRAStatus must map to Active ParticipantStatus");

        // Verify Closed -> Completed mapping via close path
        lraClient.closeLRA(childId1);
        LRAStatus closedStatus = getStatus(childId1);
        if (closedStatus != null) {
            ParticipantStatus afterClose = lraClient.getNestedLRAStatus(childId1);
            assertEquals(ParticipantStatus.Completed, afterClose,
                    "Closed LRAStatus must map to Completed ParticipantStatus");
        }
        lraClient.closeLRA(parentId1);

        // Verify Cancelled -> Compensated mapping via cancel path
        URI parentId2 = lraClient.startLRA(testName + "-parent2");
        URI childId2 = lraClient.startLRA(parentId2, testName + "-child2", 0L, ChronoUnit.SECONDS);

        lraClient.cancelLRA(childId2);
        LRAStatus cancelledStatus = getStatus(childId2);
        if (cancelledStatus != null) {
            ParticipantStatus afterCancel = lraClient.getNestedLRAStatus(childId2);
            assertEquals(ParticipantStatus.Compensated, afterCancel,
                    "Cancelled LRAStatus must map to Compensated ParticipantStatus");
        }
        lraClient.cancelLRA(parentId2);
    }

    /**
     * Per the @Status participant contract, a participant that is no longer aware
     * of an LRA must return 410 (Gone). After forgetting a nested LRA, the status
     * endpoint should return 410.
     */
    @Test
    public void testForgottenNestedLRAReturns410() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        lraClient.completeNestedLRA(childId);
        lraClient.forgetNestedLRA(childId);

        try (Response response = rawNestedStatus(childId)) {
            assertEquals(410, response.getStatus(),
                    "A forgotten nested LRA must return 410 (Gone) per the @Status participant contract");
        }

        lraClient.closeLRA(parentId);
    }

    // ===================================================================
    // Eventual Compensation Test
    // ===================================================================

    /**
     * Test the eventual compensation pattern described in the MicroProfile LRA spec.
     * A participant's Compensate method returns Compensating (HTTP 202 Accepted)
     * indicating that compensation is still in progress. The coordinator must retry
     * via recovery until the participant returns Compensated.
     *
     * The test verifies the Cancelling transient state while the participant returns
     * Compensating, and then verifies the terminal Cancelled state after recovery.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "signal LRA cancelled after eventual compensation", targetClass = "io.narayana.lra.coordinator.domain.model.LongRunningAction", targetMethod = "updateState(org.eclipse.microprofile.lra.annotation.LRAStatus, boolean)", targetLocation = "AT EXIT", condition = "$! && $1 == org.eclipse.microprofile.lra.annotation.LRAStatus.Cancelled", action = "io.narayana.lra.coordinator.domain.model.BytemanHelper.signalRendezvous(\"eventual-compensate-done\")")
    })
    public void testEventualCompensation() throws Exception {
        BytemanHelper.createRendezvous("eventual-compensate-done");
        LRAService service = LRARecoveryModule.getService();

        int compensations = compensateCount.get();

        // accept=1 causes the first @Compensate call to return Compensating (HTTP 202),
        // cancel=true triggers cancellation when the business method returns 500
        Response response = client.target(TestPortProvider.generateURL("/base/test/start-end"))
                .queryParam("accept", "1")
                .queryParam("cancel", "true")
                .request()
                .get();
        String lra = response.readEntity(String.class);
        URI lraId = new URI(lra);

        // the first compensate returned Compensating so the LRA should still be recovering
        try {
            service.getLRA(lraId);
        } catch (NotFoundException e) {
            org.junit.jupiter.api.Assertions.fail(
                    "LRA should still be in Cancelling state after first compensate returned Compensating: "
                            + e.getMessage());
        }

        LRAStatus transientStatus = getStatus(lraId);
        assertEquals(LRAStatus.Cancelling, transientStatus,
                "LRA must be in Cancelling transient state while participant returns Compensating");

        assertEquals(compensations, compensateCount.get(),
                "compensate count should not have incremented (participant returned Compensating)");

        // trigger a recovery scan - the coordinator retries the compensate call
        service.recover();

        assertTrue(BytemanHelper.awaitRendezvous("eventual-compensate-done", 10),
                "Timed out waiting for LRA to reach Cancelled after eventual compensation");

        LRAStatus finalStatus = getStatus(lraId);
        assertTrue(finalStatus == null || finalStatus == LRAStatus.Cancelled,
                testName + ": LRA did not finish after eventual compensation");
        assertEquals(compensations + 1, compensateCount.get(),
                "compensate count should have incremented after recovery retry");

        BytemanHelper.removeRendezvous("eventual-compensate-done");
    }

    // ===================================================================
    // Nested Endpoint Spec Compliance Tests
    // ===================================================================

    /**
     * @Complete returns 200 with Completed when the nested LRA completes successfully.
     */
    @Test
    public void testNestedCompleteReturns200ForCompleted() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        try (Response response = rawNestedComplete(childId)) {
            assertEquals(200, response.getStatus(),
                    "@Complete must return 200 for successful completion");
            assertEquals(ParticipantStatus.Completed.name(), response.readEntity(String.class));
        }

        lraClient.closeLRA(parentId);
    }

    /**
     * @Compensate returns 200 with Compensated when the nested LRA compensates successfully.
     */
    @Test
    public void testNestedCompensateReturns200ForCompensated() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        try (Response response = rawNestedCompensate(childId)) {
            assertEquals(200, response.getStatus(),
                    "@Compensate must return 200 for successful compensation");
            assertEquals(ParticipantStatus.Compensated.name(), response.readEntity(String.class));
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * @Complete returns 410 for a non-existent nested LRA.
     */
    @Test
    public void testNestedCompleteReturns410ForUnknown() {
        URI fakeChild = URI.create(coordinatorPath + "/unknown-nested-lra?ParentLRA=fake");

        try (Response response = rawNestedComplete(fakeChild)) {
            assertEquals(410, response.getStatus(),
                    "@Complete must return 410 for a non-existent nested LRA");
        }
    }

    /**
     * @Compensate returns 410 for a non-existent nested LRA.
     */
    @Test
    public void testNestedCompensateReturns410ForUnknown() {
        URI fakeChild = URI.create(coordinatorPath + "/unknown-nested-lra?ParentLRA=fake");

        try (Response response = rawNestedCompensate(fakeChild)) {
            assertEquals(410, response.getStatus(),
                    "@Compensate must return 410 for a non-existent nested LRA");
        }
    }

    /**
     * @Status returns 200 with the current ParticipantStatus for a known nested LRA.
     */
    @Test
    public void testNestedStatusReturns200ForKnown() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        try (Response response = rawNestedStatus(childId)) {
            assertEquals(200, response.getStatus(),
                    "@Status must return 200 for a known nested LRA");
            assertEquals(ParticipantStatus.Active.name(), response.readEntity(String.class));
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * @Status returns 410 for a non-existent nested LRA.
     */
    @Test
    public void testNestedStatusReturns410ForUnknown() {
        URI fakeChild = URI.create(coordinatorPath + "/unknown-nested-lra?ParentLRA=fake");

        try (Response response = rawNestedStatus(fakeChild)) {
            assertEquals(410, response.getStatus(),
                    "@Status must return 410 for a non-existent nested LRA");
        }
    }

    /**
     * @Forget returns 200 for a known nested LRA.
     */
    @Test
    public void testNestedForgetReturns200ForKnown() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        lraClient.completeNestedLRA(childId);

        try (Response response = rawNestedForget(childId)) {
            assertEquals(200, response.getStatus(),
                    "@Forget must return 200 for a known nested LRA");
        }

        lraClient.closeLRA(parentId);
    }

    /**
     * @Forget returns 410 for a non-existent nested LRA.
     */
    @Test
    public void testNestedForgetReturns410ForUnknown() {
        URI fakeChild = URI.create(coordinatorPath + "/unknown-nested-lra?ParentLRA=fake");

        try (Response response = rawNestedForget(fakeChild)) {
            assertEquals(410, response.getStatus(),
                    "@Forget must return 410 for a non-existent nested LRA");
        }
    }

    /**
     * @Forget uses HTTP DELETE per the MicroProfile LRA specification.
     *         A PUT to the forget endpoint should return 405 (Method Not Allowed).
     */
    @Test
    public void testNestedForgetRequiresDelete() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        lraClient.completeNestedLRA(childId);

        String nestedUrl = nestedUrl(childId);
        try (Response response = client.target(nestedUrl + "/" + FORGET)
                .request().put(Entity.text(""))) {
            assertEquals(405, response.getStatus(),
                    "@Forget endpoint must reject PUT requests (spec requires DELETE)");
        }

        lraClient.forgetNestedLRA(childId);
        lraClient.closeLRA(parentId);
    }

    // ===================================================================
    // Nested Endpoint: 202 (in-progress) and 409 (failure) Tests
    // ===================================================================

    /**
     * With API version 2.0, @Complete returns 202 with Completing when a participant
     * in the nested LRA is unreachable.
     */
    @Test
    public void testNestedCompleteReturns202ForCompletingWithV2() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        enlistUnreachableParticipantInLRA(childId);

        try (Response response = rawNestedCompleteWithVersion(childId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "@Complete with v2.0 must return 202 when participants have not all responded");
            assertEquals(ParticipantStatus.Completing.name(), response.readEntity(String.class),
                    "@Complete 202 body must be the ParticipantStatus Completing");
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * With API version 2.0, @Compensate returns 202 with Compensating when a participant
     * in the nested LRA is unreachable.
     */
    @Test
    public void testNestedCompensateReturns202ForCompensatingWithV2() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        enlistUnreachableParticipantInLRA(childId);

        try (Response response = rawNestedCompensateWithVersion(childId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "@Compensate with v2.0 must return 202 when participants have not all responded");
            assertEquals(ParticipantStatus.Compensating.name(), response.readEntity(String.class),
                    "@Compensate 202 body must be the ParticipantStatus Compensating");
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * With API version 2.0, @Complete returns 409 with FailedToComplete when a participant
     * in the nested LRA reports failure.
     */
    @Test
    public void testNestedCompleteReturns409ForFailedToCompleteWithV2() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        enlistFailingParticipant(childId);

        try (Response response = rawNestedCompleteWithVersion(childId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(409, response.getStatus(),
                    "@Complete with v2.0 must return 409 when a participant failed to complete");
            assertEquals(ParticipantStatus.FailedToComplete.name(), response.readEntity(String.class),
                    "@Complete 409 body must be the ParticipantStatus FailedToComplete");
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * With API version 2.0, @Compensate returns 409 with FailedToCompensate when a participant
     * in the nested LRA reports failure.
     */
    @Test
    public void testNestedCompensateReturns409ForFailedToCompensateWithV2() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        enlistFailingParticipant(childId);

        try (Response response = rawNestedCompensateWithVersion(childId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(409, response.getStatus(),
                    "@Compensate with v2.0 must return 409 when a participant failed to compensate");
            assertEquals(ParticipantStatus.FailedToCompensate.name(), response.readEntity(String.class),
                    "@Compensate 409 body must be the ParticipantStatus FailedToCompensate");
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * With legacy API versions (pre-2.0), @Complete returns 200 even for non-terminal states.
     */
    @Test
    public void testNestedCompleteReturns200ForLegacyVersion() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        enlistUnreachableParticipantInLRA(childId);

        try (Response response = rawNestedComplete(childId)) {
            assertEquals(200, response.getStatus(),
                    "@Complete with default version must return 200 for backward compatibility");
            assertEquals(ParticipantStatus.Completing.name(), response.readEntity(String.class),
                    "Body must still contain the ParticipantStatus");
        }

        lraClient.cancelLRA(parentId);
    }

    /**
     * With legacy API versions (pre-2.0), @Compensate returns 200 even for failed states.
     */
    @Test
    public void testNestedCompensateReturns200ForLegacyVersion() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        enlistFailingParticipant(childId);

        try (Response response = rawNestedCompensate(childId)) {
            assertEquals(200, response.getStatus(),
                    "@Compensate with default version must return 200 for backward compatibility");
            assertEquals(ParticipantStatus.FailedToCompensate.name(), response.readEntity(String.class),
                    "Body must still contain the ParticipantStatus");
        }

        lraClient.cancelLRA(parentId);
    }

    // ===================================================================
    // Nested @Status: verify body for each LRA state
    // ===================================================================

    /**
     * @Status returns Completed after a successful completion.
     */
    @Test
    public void testNestedStatusReturnsCompletedAfterComplete() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        rawNestedComplete(childId).close();

        try (Response response = rawNestedStatus(childId)) {
            assertEquals(200, response.getStatus());
            assertEquals("Completed", response.readEntity(String.class),
                    "@Status must return Completed after successful completion");
        }

        lraClient.closeLRA(parentId);
    }

    /**
     * After successful compensation, the coordinator removes the nested LRA (terminal state).
     *
     * @Status must return 410 since the participant is no longer aware of the LRA.
     */
    @Test
    public void testNestedStatusReturns410AfterCompensate() {
        URI parentId = lraClient.startLRA(testName + "-parent");
        URI childId = lraClient.startLRA(parentId, testName + "-child", 0L, ChronoUnit.SECONDS);

        rawNestedCompensate(childId).close();

        try (Response response = rawNestedStatus(childId)) {
            assertEquals(410, response.getStatus(),
                    "@Status must return 410 after the nested LRA has been compensated and cleaned up");
        }

        lraClient.cancelLRA(parentId);
    }

    private void enlistUnreachableParticipantInLRA(URI lraId) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        String prefix = "http://localhost:39999/unreachable";
        String linkHeader = String.join(",",
                makeLink(prefix, COMPLETE),
                makeLink(prefix, COMPENSATE));

        try (Response response = client.target(lraUrl).request().put(Entity.text(linkHeader))) {
            assertEquals(200, response.getStatus(),
                    "Unexpected status enlisting unreachable participant: " + response.readEntity(String.class));
        }
    }

    // --- Raw HTTP helpers for nested endpoints ---

    private String nestedUrl(URI childId) {
        String encoded = java.net.URLEncoder.encode(childId.toASCIIString(), java.nio.charset.StandardCharsets.UTF_8);
        return coordinatorPath + "/" + NESTED_COORDINATOR_PATH_NAME + "/" + encoded;
    }

    private Response rawNestedComplete(URI childId) {
        return client.target(nestedUrl(childId) + "/" + COMPLETE)
                .request().put(Entity.text(""));
    }

    private Response rawNestedCompleteWithVersion(URI childId, String apiVersion) {
        return client.target(nestedUrl(childId) + "/" + COMPLETE)
                .request()
                .header(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .put(Entity.text(""));
    }

    private Response rawNestedCompensate(URI childId) {
        return client.target(nestedUrl(childId) + "/" + COMPENSATE)
                .request().put(Entity.text(""));
    }

    private Response rawNestedCompensateWithVersion(URI childId, String apiVersion) {
        return client.target(nestedUrl(childId) + "/" + COMPENSATE)
                .request()
                .header(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .put(Entity.text(""));
    }

    private Response rawNestedStatus(URI childId) {
        return client.target(nestedUrl(childId) + "/" + STATUS)
                .request().get();
    }

    private Response rawNestedForget(URI childId) {
        return client.target(nestedUrl(childId) + "/" + FORGET)
                .request().delete();
    }
}
