/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Test various client side coordinator load balancing strategies.
 * <p>
 * The setup is similar to {@link LRATest} but it needs to {@link RunWith} Parameterized
 * (for each configured load balancing strategy) whereas LRATest requires {@link RunWith} BMUnitRunner
 */
@RunWith(Parameterized.class)
public class InvalidLBTest extends LRATestBase {
    private NarayanaLRAClient lraClient;
    private Client client;
    int[] ports = { 8081, 8082 };

    // the list of parameters to use for the test
    @Parameterized.Parameters(name = "#{index}, lb_method: {0}")
    public static Iterable<?> parameters() {
        return List.of("invalid-lb-algorithm");
    }

    // the value of the parameterized variable taken from the LBAlgorithms annotation
    @Parameterized.Parameter
    public String lb_method;

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
        System.setProperty("lra.coordinator.url", TestPortProvider.generateURL('/' + COORDINATOR_PATH_NAME));
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

        if (lb_method != null)
            System.setProperty(NarayanaLRAClient.COORDINATOR_LB_METHOD_KEY, lb_method);

        lraClient = new NarayanaLRAClient();

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
            if (lraClient != null) {
                lraClient.close();
            }
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

    @Test
    @LBAlgorithms({
            "invalid-lb-algorithm"
    })
    public void testInvalidLBAlgorithm() {
        assertFalse("should not be allowed to load balance with an invalid algorithm",
                lraClient.isLoadBalancing());

        // validate that LRAs can be started without a load balancer
        URI lra1 = lraClient.startLRA("testTwo_first");
        Current.pop(); // to avoid the next LRA being nested
        URI lra2 = lraClient.startLRA("testTwo_second");
        Current.pop();

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
    }
}
