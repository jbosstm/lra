/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import static io.narayana.lra.arquillian.resource.LRAListener.LRA_LISTENER_UNTIMED_ACTION;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.resource.LRAListener;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * This test class testes that an LRA is cancelled after lra-coordinator is restarted.
 * The LRA transaction is started before lra-coordinator gets restarted and checked
 * after lra-coordinator recovered completely.
 */
@RunAsClient
public class LRACoordinatorRecoveryIT extends UnmanagedTestBase {

    // This class needs to be re-factored if (and when) there will be the possibility
    // to manually start and stop lra-coordinator in Quarkus through Arquillian.

    private Client client;
    private NarayanaLRAClient lraClient;
    private static String LRA_COORDINATOR_CONTAINER_QUALIFIER;
    private static String LRA_PARTICIPANT_CONTAINER_QUALIFIER;
    private static String LRA_COORDINATOR_DEPLOYMENT_QUALIFIER;
    private static final String LRA_PARTICIPANT_DEPLOYMENT_QUALIFIER = "lra-participant";
    private static Path storeDir;

    private static final Long LONG_TIMEOUT = 600000L; // 10 minutes
    private static final Long SHORT_TIMEOUT = 10000L; // 10 seconds
    private static final Map<String, String> containerDeploymentMap = new HashMap<>();

    public String testName;

    @BeforeAll
    public static void beforeClass() {

        storeDir = Paths.get(String.format("%s/standalone/data/wfly_lra_objectstore", System.getenv("JBOSS_HOME")));

        LRA_COORDINATOR_CONTAINER_QUALIFIER = System.getProperty("arquillian.lra.coordinator.container.qualifier");
        if (LRA_COORDINATOR_CONTAINER_QUALIFIER == null || LRA_COORDINATOR_CONTAINER_QUALIFIER.isEmpty()) {
            fail("The System Property \"arquillian.lra.coordinator.container.qualifier\" is not defined");
        }

        LRA_PARTICIPANT_CONTAINER_QUALIFIER = System.getProperty("arquillian.lra.participant.container.qualifier");
        if (LRA_PARTICIPANT_CONTAINER_QUALIFIER == null || LRA_PARTICIPANT_CONTAINER_QUALIFIER.isEmpty()) {
            fail("The System Property \"arquillian.lra.participant.container.qualifier\" is not defined");
        }

        LRA_COORDINATOR_DEPLOYMENT_QUALIFIER = System.getProperty("arquillian.lra.coordinator.deployment.qualifier");
        if (LRA_COORDINATOR_DEPLOYMENT_QUALIFIER == null || LRA_COORDINATOR_DEPLOYMENT_QUALIFIER.isEmpty()) {
            fail("The System Property \"lra.coordinator.container.qualifier\" is not defined");
        }

        containerDeploymentMap.put(LRA_COORDINATOR_CONTAINER_QUALIFIER, "");
        containerDeploymentMap.put(LRA_PARTICIPANT_CONTAINER_QUALIFIER, LRA_PARTICIPANT_DEPLOYMENT_QUALIFIER);
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
        LRALogger.logger.debugf("Starting test %s", testName);

        client = ClientBuilder.newClient();
        lraClient = new NarayanaLRAClient();

        // Cleans Object Store in case there are transactions from previous tests
        clearRecoveryLogFromFS();
        for (Map.Entry<String, String> entry : containerDeploymentMap.entrySet()) {
            startContainer(entry.getKey(), entry.getValue());
        }
    }

    @AfterEach
    public void after() {
        if (client != null) {
            client.close();
        }
        for (Map.Entry<String, String> entry : containerDeploymentMap.entrySet()) {
            stopContainer(entry.getKey(), entry.getValue());
        }
        clearRecoveryLogFromFS();
    }

    /**
     * Arquillian uses this method to deploy lra-participant to the container targeted with @TargetsContainer.
     * Together with the extension LRACoordinatorExtension, a Server-Client environment is created with two
     * Wildfly containers, hosting lra-coordinator and lra-participant respectively.
     */
    @Deployment(name = LRA_PARTICIPANT_DEPLOYMENT_QUALIFIER, testable = false, managed = false)
    @TargetsContainer("lra-participant-server")
    public static WebArchive deploy() {
        return Deployer.createDeployment(LRA_PARTICIPANT_DEPLOYMENT_QUALIFIER, LRAListener.class);
    }

    @Test
    public void lraCoordinatorShortTimeoutLRA(
            @ArquillianResource @OperateOnDeployment(LRA_PARTICIPANT_DEPLOYMENT_QUALIFIER) URL baseURL)
            throws URISyntaxException {

        String lraId;
        URI lraListenerURI = UriBuilder.fromUri(baseURL.toURI()).path(LRAListener.LRA_LISTENER_PATH).build();

        // Starts an LRA with a short time limit by invoking a resource annotated with @LRA. The time limit is
        // defined as LRAListener.LRA_SHORT_TIMELIMIT
        Response response = client
                .target(lraListenerURI)
                .path(LRAListener.LRA_LISTENER_ACTION)
                .request()
                .put(null);

        Assertions.assertEquals(200, response.getStatus(), "LRA participant action");

        // getting the lra ID before a recovery is done
        // The LRA transaction could have been started via lraClient but it is useful to test the filters as well
        lraId = getFirstLRAFromFS();
        assertNotNull(lraId, "A new LRA should have been added to the object store before the JVM was halted.");
        lraId = String.format("%s/%s", lraClient.getCoordinatorUrl(), lraId);
        // Restarts lra-coordinator to simulate a crash
        stopContainer(LRA_COORDINATOR_CONTAINER_QUALIFIER, "");
        doWait((LRAListener.LRA_SHORT_TIMELIMIT) * 1000);
        startContainer(LRA_COORDINATOR_CONTAINER_QUALIFIER, "");

        // we might need to wait a little longer so that the LRA status moves from
        // Cancelling to Cancelled
        LRAStatus status = getStatus(new URI(lraId));
        LRALogger.logger.infof("%s: Status after restart is %s%n", status == null ? "GONE" : status.name());
        for (int i = 0; i < 100 && status != null && status != LRAStatus.Cancelled; i++) {
            LRALogger.logger.warnf("%s: Status after restart is %s%n", status == null ? "GONE" : status.name());
            doWait(500);
            status = getStatus(new URI(lraId));
        }

        // null status is also accepted because the lra has already been cancelled and
        // removed
        Assertions.assertTrue(status == null || status == LRAStatus.Cancelled,
                String.format("LRA %s should have cancelled but was %s", lraId, status));

        // Verifies that the resource was notified that the LRA finished
        String listenerStatus = getStatusFromListener(lraListenerURI);

        assertEquals(
                LRAStatus.Cancelled.name(), listenerStatus,
                String.format("The service lra-listener should have been told that the final state of the LRA %s was cancelled",
                        lraId));
    }

    @Test
    public void lraCoordinatorRecoveryTwoLRAs(
            @ArquillianResource @OperateOnDeployment(LRA_PARTICIPANT_DEPLOYMENT_QUALIFIER) URL deploymentUrl)
            throws URISyntaxException {

        URI lraListenerURI = UriBuilder.fromUri(deploymentUrl.toURI()).path(LRAListener.LRA_LISTENER_PATH).build();

        // Starts an LRA with a long timeout to validate that long LRAs do not finish early during recovery
        URI longLRA = lraClient.startLRA(null, "Long Timeout Recovery Test", LONG_TIMEOUT, ChronoUnit.MILLIS);
        // Starts an LRA with a short timeout to validate that short LRAs (which timed out when the coordinator was unavailable) are cancelled
        URI shortLRA = lraClient.startLRA(null, "Short Timeout Recovery Test", SHORT_TIMEOUT, ChronoUnit.MILLIS);

        stopContainer(LRA_COORDINATOR_CONTAINER_QUALIFIER, "");
        doWait(SHORT_TIMEOUT);
        startContainer(LRA_COORDINATOR_CONTAINER_QUALIFIER, "");

        LRAStatus longStatus = getStatus(longLRA);
        LRAStatus shortStatus = getStatus(shortLRA);

        Assertions.assertEquals(LRAStatus.Active.name(), longStatus.name(), "LRA with long timeout should still be active");
        Assertions.assertTrue(shortStatus == null ||
                LRAStatus.Cancelled.equals(shortStatus) || LRAStatus.Cancelling.equals(shortStatus),
                "LRA with short timeout should not be active");

        // Using lra-participant, a new LRA transaction is started as sub-transaction of the long LRA transaction
        // started directly through lra-coordinator from the test class. lra-participant will keep track of the
        // state of this new transaction.
        try (Response response = client.target(lraListenerURI).path(LRA_LISTENER_UNTIMED_ACTION)
                .request()
                .header(LRA_HTTP_CONTEXT_HEADER, longLRA)
                .put(Entity.text(""))) {

            Assertions.assertEquals(200, response.getStatus(), "LRA participant action");
        }

        lraClient.closeLRA(longLRA);

        // Checks that lra-participant was notified when the long LRA transaction was closed
        String listenerStatus = getStatusFromListener(lraListenerURI);

        assertEquals(LRAStatus.Closed.name(), listenerStatus,
                "LRA listener should have been told that the final state of the LRA was closed");
    }

    // Private methods

    private void doWait(long millis) {
        if (millis > 0L) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                LRALogger.logger.errorf("An exception has been thrown while the test was trying to wait for %d milliseconds",
                        millis);
                Assertions.fail();
            }
        }
    }

    private int recover() {
        Client client = ClientBuilder.newClient();

        // A byteman rule could be used to sync the restart of lra-coordinator, until just wait.
        // This delay makes sure that the 2-phase recovery mechanism has started
        doWait(LRAListener.LRA_SHORT_TIMELIMIT * 1000);

        try (Response response = client.target(lraClient.getRecoveryUrl())
                .request()
                .get()) {

            Assertions.assertEquals(200,
                    response.getStatus(),
                    "Unexpected status from recovery call to " + lraClient.getRecoveryUrl());

            // The result will be a List<LRAStatusHolder> of recovering LRAs but we just need the count
            String recoveringLRAs = response.readEntity(String.class);

            return recoveringLRAs.length() - recoveringLRAs.replace(".", "").length();
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        }
    }

    /**
     * Asks {@link LRAListener} if it has been notified of the final outcome of the LRA transaction
     * associated with it.
     */
    private String getStatusFromListener(URI lraListenerURI) {
        try (Response response = client.target(lraListenerURI).path(LRAListener.LRA_LISTENER_STATUS)
                .request()
                .get()) {

            Assertions.assertEquals(200, response.getStatus(), "LRA participant HTTP status");

            return response.readEntity(String.class);
        }
    }

    /**
     * <p>
     * This method fetches the first LRA transaction from the File-System Object Store (ShadowNoFileLockStore).
     * </p>
     *
     * @return The ID of the first LRA transaction
     */
    String getFirstLRAFromFS() {
        Path lraDir = Paths.get(storeDir.toString(), "ShadowNoFileLockStore", "defaultStore", LongRunningAction.getType());

        try {
            Optional<Path> lra = Files.list(new File(lraDir.toString()).toPath()).findFirst();

            return lra.map(path -> path.getFileName().toString()).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * <p>
     * This method fetches the last LRA transaction from lra-coordinator.
     * </p>
     *
     * @return The ID of the last LRA transaction
     */
    String getLastLRAFromObjectStore() {

        List<LRAData> LRAList = new ArrayList<>();

        try {
            LRAList = lraClient.getAllLRAs();
        } catch (Exception ex) {
            LRALogger.logger.error(ex.getMessage());
        }

        return (LRAList.isEmpty() ? null : LRAList.get(LRAList.size() - 1).getLraIdAsString());
    }

    /**
     * <p>
     * This method physically deletes the folder (and all its content) where the File-System Object Store is used.
     */
    private void clearRecoveryLogFromFS() {
        try (Stream<Path> recoveryLogFiles = Files.walk(storeDir)) {
            recoveryLogFiles
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ioe) {
            // transaction logs will only exists after there has been a previous run
            LRALogger.logger.debugf(ioe, "Cannot finish delete operation on recovery log dir '%s'", storeDir);
        }
    }

    /**
     * <p>
     * This method deletes LRA transactions through lra-coordinator.
     */
    private void clearRecoveryLogFromObjectStore() {

        List<LRAData> LRAList = new ArrayList<>();

        try {
            LRAList = lraClient.getAllLRAs();
        } catch (Exception ex) {
            LRALogger.logger.error(ex.getMessage());
        }

        LRAList.forEach(x -> lraClient.cancelLRA(x.getLraId()));
    }
}
