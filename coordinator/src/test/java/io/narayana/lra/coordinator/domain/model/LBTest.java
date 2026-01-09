/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static io.narayana.lra.client.NarayanaLRAClient.LB_METHOD_ROUND_ROBIN;
import static io.narayana.lra.client.NarayanaLRAClient.LB_METHOD_STICKY;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.Current;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test various coordinator load balancing strategies.
 * <p>
 * The setup is similar to {@link LRATest} but it needs {@link RunWith} Parameterized
 * (on the various load balancing strategies) whereas LRATest requires {@link RunWith} BMUnitRunner.
 * Also, these tests do not need to deploy participants.
 */
public class LBTest extends LRATestBase {
    private NarayanaLRAClient lraClient;
    private Client client;
    int[] ports = { 8081, 8082 };
    public String lb_method;

    // parameters used for setting the lb_method field for parameterized test runs
    public static Iterable<?> parameters() {
        return Arrays.asList(NarayanaLRAClient.NARAYANA_LRA_SUPPORTED_LB_METHODS);
    }

    public String testName;

    @ApplicationPath("/")
    public static class LRACoordinator extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            return classes;
        }
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
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

        if (lb_method != null) {
            System.setProperty(NarayanaLRAClient.COORDINATOR_LB_METHOD_KEY, lb_method);
        }

        client = ClientBuilder.newClient();

        for (UndertowJaxrsServer server : servers) {
            server.deploy(LRACoordinator.class);
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
            assertNull(uri, testName + ": thread should not be associated with any LRAs");
        }
    }

    // run a test multiple times parameterised by the algorithms defined by an @LBAlgorithms annotation
    @ParameterizedTest(name = "#{index}, lb_method: {0}")
    @MethodSource("parameters")
    public void testMultipleCoordinators(String lb_method) {
        initLBTest(lb_method);
        URI lra1 = lraClient.startLRA("testTwo_first");
        Current.pop(); // to avoid the next LRA from being nested
        URI lra2 = lraClient.startLRA("testTwo_second");
        Current.pop();

        switch (lb_method) {
            case LB_METHOD_ROUND_ROBIN:
                // verify that the two LRAs were load balanced in a round-robin fashion:
                assertNotEquals(lra1.getPort(), lra2.getPort(), "LRAs should have been created by different coordinators");
                break;
            case LB_METHOD_STICKY:
                // verify that the two LRAs were created by the same coordinator:
                assertEquals(lra1.getPort(), lra2.getPort(), "LRAs should have been created by the same coordinator");
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

        assertTrue(status1 == null || status1 == LRAStatus.Closed, "1st LRA finished in wrong state");
        assertTrue(status2 == null || status2 == LRAStatus.Closed, "2nd LRA finished in wrong state");
    }

    // test failover of coordinators (ie if one is unavailable then the next one in the list is tried)
    @ParameterizedTest(name = "#{index}, lb_method: {0}")
    @ValueSource(strings = { NarayanaLRAClient.LB_METHOD_ROUND_ROBIN, NarayanaLRAClient.LB_METHOD_STICKY })
    public void testCoordinatorFailover(String lb_method) {
        initLBTest(lb_method);
        URI lra1 = runLRA("testCoordinatorFailover-first", true);
        URI lra2 = runLRA("testCoordinatorFailover-second", true);

        assertNotNull(lra1);
        assertNotNull(lra2);

        switch (lb_method) {
            case LB_METHOD_ROUND_ROBIN:
                assertNotEquals(lra1.getPort(), lra2.getPort(), "round-robin used the same coordinator");
                break;
            case LB_METHOD_STICKY:
                assertEquals(lra1.getPort(), lra2.getPort(), "round-robin used different coordinators");
                break;
            default:
                fail("unexpected lb method");
        }

        servers[0].stop(); // stop the first one so that we can check that the load balancer operates as expected

        try {
            URI lra3 = runLRA("testCoordinatorFailover-third", false);

            if (LB_METHOD_STICKY.equals(lb_method)) {
                assertNull(lra3, "should not be able to start an LRA with sticky if the original one is down");
            } else {
                URI lra4 = runLRA("testCoordinatorFailover-fourth", false);
                assertNotNull(lra3); // round-robin means that the next coordinator in the list is tried
                assertNotNull(lra4);
                assertEquals(lra3.getPort(), lra4.getPort(), "different coordinators should not have been used");
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

    public void initLBTest(String lb_method) {
        this.lb_method = lb_method;
        System.setProperty(NarayanaLRAClient.COORDINATOR_LB_METHOD_KEY, lb_method);
        lraClient = new NarayanaLRAClient();
        if (!lraClient.isLoadBalancing()) {
            fail("client should be load balancing (look for message id 25046 in the logs for the reason why)");
        }
        if (lraClient.getCurrent() != null) {
            // clear it since it isn't caused by this test (tests do the assertNull in the
            // @After test method)
            LRALogger.logger.warnf("before test %s: current thread should not be associated with any LRAs", testName);
            lraClient.clearCurrent(true);
        }
    }
}
