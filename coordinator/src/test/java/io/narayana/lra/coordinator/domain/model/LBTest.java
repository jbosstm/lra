/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static io.narayana.lra.client.internal.NarayanaLRAClient.LB_METHOD_ROUND_ROBIN;
import static io.narayana.lra.client.internal.NarayanaLRAClient.LB_METHOD_STICKY;
import static io.narayana.lra.client.internal.NarayanaLRAClient.LRA_COORDINATOR_URL_KEY;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Test various coordinator load balancing strategies.
 * <p>
 * The setup is similar to {@link LRATest} but it needs {@link RunWith} Parameterized
 * (on the various load balancing strategies) whereas LRATest requires {@link RunWith} BMUnitRunner.
 * Also, these tests do not need to deploy participants.
 */
@RunWith(Parameterized.class)
public class LBTest extends LRATestBase {
    private NarayanaLRAClient lraClient;
    private Client client;
    int[] ports = { 8081, 8082 };

    // the value of the parameterized variable to be set according to the values in the LBAlgorithms annotation
    @Parameterized.Parameter
    public String lb_method;

    // parameters used for setting the lb_method field for parameterized test runs
    @Parameterized.Parameters(name = "#{index}, lb_method: {0}")
    public static Iterable<?> parameters() {
        return Arrays.asList(NarayanaLRAClient.NARAYANA_LRA_SUPPORTED_LB_METHODS);
    }

    // the rule that populates the lb_method field for each run of a parameterized method
    @Rule
    public LBTestRule testRule = new LBTestRule();

    @Rule
    public TestName testName = new TestName();

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
        System.setProperty(LRA_COORDINATOR_URL_KEY, TestPortProvider.generateURL('/' + COORDINATOR_PATH_NAME));
    }

    @Before
    public void before() {
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

        if (lb_method != null) {
            System.setProperty(NarayanaLRAClient.COORDINATOR_LB_METHOD_KEY, lb_method);
        }

        lraClient = new NarayanaLRAClient();

        if (!lraClient.isLoadBalancing()) {
            fail("client should be load balancing (look for message id 25046 in the logs for the reason why)");
        }

        client = ClientBuilder.newClient();

        for (UndertowJaxrsServer server : servers) {
            server.deploy(LRACoordinator.class);
        }

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
            assertNull(testName.getMethodName() + ": thread should not be associated with any LRAs", uri);
        }
    }

    // run a test multiple times parameterised by the algorithms defined by an @LBAlgorithms annotation
    @Test
    @LBAlgorithms({
            NarayanaLRAClient.LB_METHOD_ROUND_ROBIN,
            NarayanaLRAClient.LB_METHOD_STICKY,
            NarayanaLRAClient.LB_METHOD_RANDOM,
            NarayanaLRAClient.LB_METHOD_LEAST_REQUESTS,
            NarayanaLRAClient.LB_METHOD_LEAST_RESPONSE_TIME,
            NarayanaLRAClient.LB_METHOD_POWER_OF_TWO_CHOICES
    })
    public void testMultipleCoordinators() {
        URI lra1 = lraClient.startLRA("testTwo_first");
        Current.pop(); // to avoid the next LRA from being nested
        URI lra2 = lraClient.startLRA("testTwo_second");
        Current.pop();

        switch (lb_method) {
            case LB_METHOD_ROUND_ROBIN:
                // verify that the two LRAs were load balanced in a round-robin fashion:
                assertNotEquals("LRAs should have been created by different coordinators",
                        lra1.getPort(), lra2.getPort());
                break;
            case LB_METHOD_STICKY:
                // verify that the two LRAs were created by the same coordinator:
                assertEquals("LRAs should have been created by the same coordinator",
                        lra1.getPort(), lra2.getPort());
                break;
            default:
                // other algorithms are more complex and/or are indeterminate to test - now rely on the Stork testsuite
                break;
        }

        try {
            lraClient.closeLRA(lra1);
        } catch (WebApplicationException e) {
            fail("close first LRA failed: " + e.getMessage());
        } finally {
            try {
                lraClient.closeLRA(lra2);
            } catch (WebApplicationException e2) {
                fail("close second LRA failed: " + e2.getMessage());
            }
        }

        LRAStatus status1 = getStatus(lra1);
        LRAStatus status2 = getStatus(lra2);

        assertTrue("1st LRA finished in wrong state", status1 == null || status1 == LRAStatus.Closed);
        assertTrue("2nd LRA finished in wrong state", status2 == null || status2 == LRAStatus.Closed);
    }

    // test failover of coordinators (ie if one is unavailable then the next one in the list is tried)
    @Test
    @LBAlgorithms({
            LB_METHOD_ROUND_ROBIN, LB_METHOD_STICKY
    })
    public void testCoordinatorFailover() {
        URI lra1 = runLRA("testCoordinatorFailover-first", true);
        URI lra2 = runLRA("testCoordinatorFailover-second", true);

        assertNotNull(lra1);
        assertNotNull(lra2);

        switch (lb_method) {
            case LB_METHOD_ROUND_ROBIN:
                assertNotEquals("round-robin used the same coordinator", lra1.getPort(), lra2.getPort());
                break;
            case LB_METHOD_STICKY:
                assertEquals("round-robin used different coordinators", lra1.getPort(), lra2.getPort());
                break;
            default:
                fail("unexpected lb method");
        }

        servers[0].stop(); // stop the first one so that we can check that the load balancer operates as expected

        try {
            URI lra3 = runLRA("testCoordinatorFailover-third", false);

            if (LB_METHOD_STICKY.equals(lb_method)) {
                assertNull("should not be able to start an LRA with sticky if the original one is down", lra3);
            } else {
                URI lra4 = runLRA("testCoordinatorFailover-fourth", false);
                assertNotNull(lra3); // round-robin means that the next coordinator in the list is tried
                assertNotNull(lra4);
                assertEquals("different coordinators should not have been used",
                        lra3.getPort(), lra4.getPort());
            }
        } finally {
            servers[0].start(); // restart the stopped server
        }
    }

    private URI runLRA(String clientName, boolean shouldFail) {
        try {
            URI lra = lraClient.startLRA(clientName);
            lraClient.closeLRA(lra);
            return lra;
        } catch (WebApplicationException e) {
            if (shouldFail) {
                fail("Unable to run LRA using lb method " + lb_method + ": " + e.getMessage());
            }
            return null;
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
}
