/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Application;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test various client side coordinator load balancing strategies.
 * <p>
 * The setup is similar to {@link LRATest} but it needs to {@link RunWith} Parameterized
 * (for each configured load balancing strategy) whereas LRATest requires {@link RunWith} BMUnitRunner
 */
public class InvalidLBTest extends LRATestBase {
    private NarayanaLRAClient lraClient;
    private Client client;
    int[] ports = { 8081, 8082 };

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

        System.setProperty(NarayanaLRAClient.COORDINATOR_LB_METHOD_KEY, "invalid-lb-algorithm");

        lraClient = new NarayanaLRAClient();

        client = ClientBuilder.newClient();

        for (UndertowJaxrsServer server : servers) {
            server.deploy(LRACoordinator.class);
        }

        if (lraClient.getCurrent() != null) {
            // clear it since it isn't caused by this test (tests do the assertNull in the @After test method)
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
            assertNull(uri,
                    testName + ": current thread should not be associated with any LRAs");
        }
    }

    @ParameterizedTest(name = "#{index}, lb_method: {0}")
    @ValueSource(strings = { "invalid-lb-algorithm" })
    public void testInvalidLBAlgorithm(String lb_method) {
        assertFalse(lraClient.isLoadBalancing(),
                "should not be allowed to load balance with an invalid algorithm");

        try {
            lraClient.startLRA("testInvalidLBAlgorithm");
            fail("testInvalidLBAlgorithm: should not be able to start an LRA with an invalid load balancer");
        } catch (WebApplicationException e) {
            // the documentation says that starting a new LRA with an invalid load balancer should fail with a 503
            assertEquals(SERVICE_UNAVAILABLE.getStatusCode(), e.getResponse().getStatus());
        }
    }
}
